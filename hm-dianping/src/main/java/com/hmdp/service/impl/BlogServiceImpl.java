package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {


    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 查询点赞排行前列的Blog
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBLogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据BlogId查询Blog
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询blog
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }

        // 2.查询 blog 有关用户
        queryBlogUser(blog);

        // 3.查询用户是否点赞
        isBLogLiked(blog);

        return Result.ok(blog);
    }

    private void isBLogLiked(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null){
            // 如果用户未登录直接返回,无需验证是否登录即可查看点赞情况
            return;
        }

        Long userId = user.getId();

        // 2.判断当前登录用户是否点赞
        String key=BLOG_LIKED_KEY +blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        blog.setIsLike(score != null);
    }

    /**
     * 判断用户是否点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.判断当前登录用户是否点赞
        String key=BLOG_LIKED_KEY +id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        // 3.如果未点赞则可以点赞
        if (score == null){
            // 3.1 数据库点赞数+1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2保存用户数据到Redis的Set集合中
            if (isSuccess){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            // 4.如果已经点赞,则取消点赞
            // 4.1 数据库点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2 将Redis中的Set集合中删除用户数据
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询当前Blog Top5点赞用户
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key= BLOG_LIKED_KEY +id;
        // 1.查询top5的点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);

        // 2.解析出其中的用户id
        if (top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = top5.stream().map(Long::valueOf).toList();
        // 3.根据用户id查询用户
        String join = StrUtil.join(",", ids); //将用户id拼接成字符串,供sql语句拼接使用
        List<UserDTO> userDTOS = userService.query().in("id",ids).last("ORDER BY FIELD(id,"+join+")").list() //此处要使用Order by field(join) 按照join顺序排序
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .toList();

        // 4.返回结果

        return Result.ok(userDTOS);
    }

    /**
     * 新增blog并进行推送
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店blog
        boolean isSuccess = save(blog);
        if (!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 3.查询blog作者的粉丝 select * from tb_follow where follow_user_id =?
        List<Follow> followUserId = followService.query().eq("follow_user_id", user.getId().toString()).list();

        // 4.将新发布blog推送给粉丝
        if (followUserId == null || followUserId.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        for (Follow follow : followUserId) {
            // 4.1获取粉丝id
            Long userId = follow.getUserId();
            String key=FEED_KEY+userId;
            // 4.2将新发布Blog推送到粉丝的收件箱
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }

        // 5.返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        // 2.查询收件箱 --滚动分页查询 ZRANGEBYSCORE key Max Min LIMIT offset count
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 4.解析数据:blogId minTime(时间戳) offset(与最小时间戳一样的个数)
        List<Long> ids=new ArrayList<>(typedTuples.size());
        long minTime=0;
        int os=1; //偏移量 offset
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 4.1获取id
            ids.add(Long.valueOf(typedTuple.getValue()));

            // 4.2获取分数(时间戳)
            long Time= typedTuple.getScore().longValue();
            if (minTime == Time){ //判断当前获取时间戳与minTime是否一直
                os++; //如果一致则 offset++
            }else {
                minTime=Time; //如果不一致则,将当前获取时间戳赋值给minTime
                os = 1; //offset 再次设置为 1
            }
        }

        // 5.根据id查询blog,以及和blog有关联的数据
        String idStr=StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            // 5.1查询 blog 有关用户
            queryBlogUser(blog);
            // 5.2查询用户是否点赞
            isBLogLiked(blog);
        }

        // 6.封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(os);
        scrollResult.setMinTime(minTime);
        return Result.ok(scrollResult);
    }

    /*
        查询与Blog有关的用户
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
