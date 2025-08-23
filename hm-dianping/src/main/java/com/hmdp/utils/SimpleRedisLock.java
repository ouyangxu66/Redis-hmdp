package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name; //锁的名称,不同的业务需要不同的Key即是需要不同的锁名称
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //需要调用者传递两个参数
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT=new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程标识 --添加UUID使得线程标识复杂化,标识唯一
        String threadId = Thread.currentThread().getId()+ID_PREFIX;

        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX+name, threadId, timeoutSec, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success); //避免拆箱的空指针异常
    }


    @Override
    public void unlock() {
        //执行Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX+Thread.currentThread().getId());
    }
//    @Override
//    public void unlock() {
//        // 删除锁前判断是否为自己的锁,是自己的锁才释放
//
//        //1.获取线程标识
//        String threadId = Thread.currentThread().getId()+ID_PREFIX;
//
//        //2.获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        //3.判断是否一致 --一致时释放锁,不一致时不做动作
//        if (threadId.equals(id)){
//            //4.释放锁
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//
//
//    }
}
