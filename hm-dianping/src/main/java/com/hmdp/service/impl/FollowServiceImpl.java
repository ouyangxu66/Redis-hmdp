package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.判断是关注还是取关
        Long userId = UserHolder.getUser().getId();
        String key="follows:"+userId;
        if (isFollow){
            // 2.如果是关注,则新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess){
                // 把关注用户的id,放入redis的set集合中

                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            // 3.如果是取关,则删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));

            if (isSuccess){
                //把取关用户的id从redis集合中移除
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        // 3.判断是否关注
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        String key1="follows:"+userId;

        // 2.求当前用户和目标用户的关注交集
        String key2="follows:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (intersect == null || intersect.isEmpty()){
            //如果没有交集则返回空列表
            return Result.ok(Collections.emptyList());
        }

        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).toList();

        // 4.查询用户
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();
        return Result.ok(userDTOS);
    }
}
