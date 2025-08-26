package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10); //创建一个线程池

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        //Shop shop=queryWithPassThrough(id);

        //解决缓存穿透 --调用封装好的Redis工具类
//        Shop shop = cacheClient.queryWithPassThrough(
//                    RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);


        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);

        //逻辑过期解决缓存击穿 --调用封装好的Redis工具类
//        Shop shop = cacheClient.queryWithLogicalExpire(
//                RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById,20L,TimeUnit.SECONDS);

        Shop shop = cacheClient.queryWithPassThrough(
                RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        if (shop == null){
            return Result.fail("店铺不存在");
        }
        //7.返回商铺信息
        return Result.ok(shop);
    }

    /**
     * 使用逻辑过期解决缓存击穿
     * @param id
     * @return
     */
//    public Shop queryWithLogicalExpire(Long id){
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        //1.从Redis中查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2.判断是否存在
//        if (StrUtil.isBlank(shopJson)){
//            //3.如果不存在,返回空值
//            return null;
//        }
//
//        //4.命中,需要先把json反序列化为对象
//        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
//        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
//        LocalDateTime expireTime = redisData.getExpireTime();
//
//        //5.判断是否过期
//        if (expireTime.isAfter(LocalDateTime.now())){
//            //5.1 未过期,直接返回店铺信息
//            return shop;
//        }
//        //5.2 已过期,需要缓存重建
//
//        //6.缓存重建
//        //6.1 获取互斥锁
//        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
//        boolean isLock = tryLock(lockKey);
//        //6.2 判断释放获取锁成功
//        if (isLock){
//            //6.3 成功,开启独立线程,实现缓存重建
//            CACHE_REBUILD_EXECUTOR.submit(()->{
//                try {
//                    //重建缓存
//                    this.saveShop2Redis(id,20L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                } finally {
//                    //释放锁
//                    unLock(lockKey);
//                }
//
//            });
//        }
//        //6.4 无论是否获取成功,都要返回过期商铺信息
//
//        //7.返回商铺信息
//        return shop;
//    }

    /**
     * 互斥锁解决缓存击穿
     * @param id
     * @return
     */
//    public Shop queryWithMutex(Long id){
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        //1.从Redis中查询商铺缓存
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//
//        //2.判断是否存在
//        if (StrUtil.isNotBlank(shopJson)){
//            //3.存在,直接返回商铺信息 --JSONUtil.toBean()
//            return JSONUtil.toBean(shopJson, Shop.class);
//        }
//        //判断命中的是否为空值 --处理缓存穿透问题
//        if (shopJson != null){
//            //查询到的结果为空字符串""但是不为空null,返回一个错误信息
//            return null;
//        }
//
//        //4.实现缓存重建
//        //4.1 获取互斥锁
//        String lockKey="lock:shop:"+id;
//        Shop shop=null;
//        try {
//            boolean flag = tryLock(lockKey);
//
//            //4.2 判断互斥锁是否获取成功
//            if (!flag){
//                //4.3 获取锁失败,则休眠重试
//                Thread.sleep(50);
//                return queryWithMutex(id);
//            }
//            //4.4 获取锁成功,则根据id查询数据库
//            shop = getById(id);
//            Thread.sleep(200);
//            //5.不存在,,并且
//            if (shop == null){
//                //将空值写入Redis --处理缓存穿透问题
//                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//                //返回错误信息
//                return null;
//            }
//
//            //6.存在,将商铺信息写入Redis --JSONUtil.toJsonStr()
//            String shopStr = JSONUtil.toJsonStr(shop);
//            stringRedisTemplate.opsForValue().set(key,shopStr);
//
//            //设置过期时间为三十分钟
//            stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            //7.释放互斥锁
//            unLock(lockKey);
//        }
//
//        //8.返回商铺信息
//        return shop;
//    }

    /**
     * 实现缓存穿透
     * @param id
     * @return
     */
/*    public Shop queryWithPassThrough(Long id){
        String key = RedisConstants.CACHE_SHOP_KEY + id;
        //1.从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)){
            //3.存在,直接返回商铺信息 --JSONUtil.toBean()
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的是否为空值 --处理缓存穿透问题
        if (shopJson != null){
            //为空值,返回错误信息
            return null;
        }

        //4.既没有命中Redis数据,也没有命中Redis缓存穿透数据,根据id查询商铺信息 --getById()
        Shop shop = getById(id);

        //5.不存在,,并且
        if (shop == null){
            //将空值写入Redis --处理缓存穿透问题
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //6.存在,将商铺信息写入Redis --JSONUtil.toJsonStr()
        String shopStr = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key,shopStr);

        //设置过期时间为三十分钟
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7.返回商铺信息
        return null;
    }
*/

    /**
     * 将商铺数据封装成RedisData后保存
     * @param id
     * @param expireSeconds
     * @throws InterruptedException
     */
/*    private void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.获取店铺数据
        Shop shop = getById(id);
        //模拟重建缓存延迟
        Thread.sleep(200);
        //2.将店铺数据和逻辑过期时间封装到RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.将RedisData写入Redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }*/

    /**
     * 更新商铺数据
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);

        //3.返回结果
        return Result.ok();
    }
}
