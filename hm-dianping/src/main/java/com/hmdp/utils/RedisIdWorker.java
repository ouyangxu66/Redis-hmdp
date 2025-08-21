package com.hmdp.utils;


import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.swing.text.DateFormatter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;


/**
 * 全局唯一ID生成器
 */
@Component
public class RedisIdWorker {


    private static final long BEGIN_TIMESTAMP = 1735689600; //2025年1月1日0时0分0秒的时间戳
    private static final long COUNT_BITS = 32; //位运算的左移位数

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix){

        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        //2.生成序列号
        //2.1 获取当前日期,精确到天,防止所有数据的Key值都一样且具有统计效果 --DateTimeFormatter
        String date= now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        //2.2 自增长 --increment()
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        //3.拼接时间戳和序列号并返回 --拼接使用位运算
        return timestamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC); //参数为时区,返回值为具体的秒数时间戳
        System.out.println("second="+second);

    }

}
