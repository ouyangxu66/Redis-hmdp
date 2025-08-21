package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime; //设置Key的逻辑过期时间
    private Object data;  //存入Redis的数据
}
