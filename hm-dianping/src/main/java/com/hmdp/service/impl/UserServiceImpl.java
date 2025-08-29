package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    /**
     * 发送手机验证码
     */
    public Result sendCode(String phone, HttpSession session) {
        //1.判断手机号是否符合要求
        if (RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合,则报错
            return Result.fail("手机号格式不正确,请重新输入");
        }
        //3.符合,则生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到Redis,并将验证码设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY +phone,code,2, TimeUnit.MINUTES);
        //5.发送验证码 --模拟发送
        log.info("成功发送验证码,验证码为:{}",code);
        //返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //如果不符合,则报错
            return Result.fail("手机号格式不正确,请重新输入");
        }
        // 2.校验验证码 --先取出Redis中的code,和前端传过来的验证码比较
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !code.equals(cacheCode)){
            //3.如果不一致,则报错
            return Result.fail("验证码错误!");
        }

        //4.根据手机号查询用户 --使用mybatis plus query()语句 / select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在 --user 是否为null
        if (user == null){
            //6.不存在则创建新用户并保存
            user=createUserWithPhone(phone);
        }
        //7.无论是否存在,登录之后将用户信息保存到Redis中
        // 7.1 随机生成token,作为登录令牌
        String token = UUID.randomUUID().toString(true);

        // 7.2 将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class); //存入的为userDTO对象,只需要存储三种信息,减少内存压力
        Map<String, Object> map = BeanUtil.beanToMap(userDTO);
        // 将Map中的值都转换为String类型，避免Redis序列化错误
        Map<String, String> stringMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            stringMap.put(entry.getKey(), entry.getValue().toString());
        }
        //T 7.3 存储
        String key = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key,stringMap);

        //T 7.4 设置token有效期为三十分钟
        stringRedisTemplate.expire(key,LOGIN_USER_TTL,TimeUnit.SECONDS);

        //8.返回token
        return Result.ok(token);
    }

    /**
     * 用户签到
     * @return
     */
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();

        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key= USER_SIGN_KEY+userId+keySuffix;

        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();

        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key= USER_SIGN_KEY+userId+keySuffix;

        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();

        // 5.获取本月截止到今天为止的所有签到记录,返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)

        );

        if (result == null || result.isEmpty()){
            return Result.ok(0);
        }

        Long num = result.get(0);
        if (num == null || num == 0){
            return Result.ok(0);
        }

        // 6.循环遍历 --计算到今天为止的连续签到次数
        int count=0;
        while (true){
            // 让这个数字与1做运算.得到数字的最后一个为bit位
            // 判断这个bit位是否为 0
            if ((num & 1) == 0){
                // 如果为0 ,则跳出循环.说明未签到
                break;
            }else {
                // 如果为1, 说明已签到, 计数器+1
                count++;
            }
            // 把数字右移一位,即抛弃最后一位bit位,继续查看下一个bit位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        //2.保存用户
        save(user);

        return user;
    }
}
