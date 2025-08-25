package com.hmdp.service.impl;

import cn.hutool.core.lang.UUID;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 将订单加入阻塞队列
     */
    // 1.创建阻塞队列:如果从阻塞队列中获取元素时,队列中没有元素,队列会将线程阻塞直到获取到元素
    private BlockingQueue<VoucherOrder> orderTasks= new ArrayBlockingQueue<>(1024*1024);

    // 2.创建一个单线程的线程池
    private static final ExecutorService SEKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 3.单线程线程池来运行阻塞队列
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                // 1.死循环,不断获取队列中的订单信息,直到获取成功
                try {
                    VoucherOrder voucherOrder=orderTasks.take(); //take()方法是阻塞方法，当队列为空时会阻塞当前线程，直到队列中有元素可用时才会返回。
                    // 2.创建订单
                    hanldeVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }

            }
        }
    }


    /**
     * 处理优惠券订单
     * 通过分布式锁确保同一用户不能重复下单，处理完成后释放锁
     *
     * @param voucherOrder 待处理的优惠券订单对象
     */
    private void hanldeVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 1.创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 2.获取锁
        boolean isLock = lock.tryLock(); //无参表示只尝试获取一次

        // 3.判断获取锁是否成功
        if (!isLock){
            // 4.获取锁失败,返回错误,不允许重试即是不允许重新下单
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }



    /**
     * 初始化方法，在对象创建完成后执行
     * 提交voucher订单处理任务到线程池，用于异步处理秒杀订单
     */
    @PostConstruct
    private void init(){
        SEKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 获取代理对象实例
    private IVoucherOrderService proxy; //

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户Id
        Long userId = UserHolder.getUser().getId();
        // 1.执行Lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        // 2.判断Lua脚本执行结果是否为0
        int r=result.intValue();
        if (r != 0){
            // 3.如果结果不为0,则没有购买资格
            return Result.fail(r ==1 ? "库存不足":"不能重复下单");
        }


        // 4.结果为0,则有购买资格,把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");

        // 5.创建订单 --订单id 用户id 代金券id
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);

        //6.将订单信息添加到阻塞队列
        orderTasks.add(voucherOrder);

        // 7.获取代理对象(事务)
        proxy = (IVoucherOrderService) AopContext.currentProxy();


        // 6.返回订单Id

        return Result.ok(orderId);
    }
// 未优化前代码
/*    @Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2.查询秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
            //尚未开始
            return Result.fail("活动尚未开始");
        }

        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            //秒杀结束
            return Result.fail("活动已经结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1){
            //库存不足
            return Result.fail("库存不足");
        }

        //5.使用分布式锁实现一人一单
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        // 获取锁
        boolean isLock = lock.tryLock(); //无参表示只尝试获取一次

        // 判断获取锁是否成功
        if (!isLock){
            //获取锁失败,返回错误,不允许重试即是不允许重新下单
            return Result.fail("不允许重复下单");
        }

        try {
            // 获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, voucher);
        } finally {
            //释放锁
            lock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //1.一人一单
        Long userId =voucherOrder.getUserId();
        //1.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //1.2 判断是否存在
        if (count > 0){
            //1.3 用户已经购买过了
            log.error("用户已经购买过了");
            return;
        }

        //2.库存充足,扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1") //set stock=stock-1
                .eq("voucher_id", voucherOrder.getVoucherId()) //where voucher_id=voucherId
//                .eq("stock",voucher.getStock()) //更新前判断查询时的库存是否与更新时的库存一致,一致时才进行库存更新操作 --高并发时,会导致大量的购买失败
                .gt("stock",0) //where stock > 0 在更新时库存大于 0 即可修改库存,即可减少由于高并发导致的有库存但是购买失败
                .update();
        if (!success){
            //3.扣减库存失败
            log.error("库存不足");
            return;
        }
        //4..创建订单
        save(voucherOrder);
    }
}
