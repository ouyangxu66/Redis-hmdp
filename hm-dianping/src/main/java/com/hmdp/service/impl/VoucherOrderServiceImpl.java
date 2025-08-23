package com.hmdp.service.impl;

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
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

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

    @Override
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
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        // 获取锁
        boolean isLock = lock.tryLock(500);

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

    }

    @Transactional
    public synchronized Result createVoucherOrder(Long voucherId, SeckillVoucher voucher) {
        //5.一人一单
        Long userId = UserHolder.getUser().getId();
        //5.1 查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2 判断是否存在
        if (count > 0){
            //用户已经购买过了
            return Result.fail("用户已经购买过一次");
        }


        //6.库存充足,扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1") //set stock=stock-1
                .eq("voucher_id", voucherId) //where voucher_id=voucherId
//                .eq("stock",voucher.getStock()) //更新前判断查询时的库存是否与更新时的库存一致,一致时才进行库存更新操作 --高并发时,会导致大量的购买失败
                .gt("stock",0) //where stock > 0 在更新时库存大于 0 即可修改库存,即可减少由于高并发导致的有库存但是购买失败
                .update();
        if (!success){
            //扣减库存失败
            return Result.fail("库存不足");
        }

        //7.创建订单 --订单id 用户id 代金券id
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(redisIdWorker.nextId("order"));
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);

        //8.保存订单
        save(voucherOrder);

        //9.返回订单id

        return Result.ok(voucherOrder.getId());
    }
}
