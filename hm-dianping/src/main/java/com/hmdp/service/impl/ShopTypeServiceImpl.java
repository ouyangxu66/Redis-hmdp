package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public List<ShopType> shopTypeList() {
        //1.查询Redis缓存中是否存在
        ListOperations<String, String> shopTypeListCache = stringRedisTemplate.opsForList();
        String key= RedisConstants.CACHE_SHOPTYPE_LIST_KEY;

        //2.存在,从Redis中获取并返回
        if (shopTypeListCache.size(key) > 0){
            List<String> list = shopTypeListCache.range(key, 0, -1);
            return list.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
        }

        //3.不存在,从数据库中查询
        List<ShopType> shopTypes = this.list();

        //4.数据库中不存在,返回报错
        if (shopTypes.isEmpty()){
            return shopTypes;
        }

        //5.数据库中存在,查询返回
        List<String> shopTypeJsonList = shopTypes.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList());

        shopTypeListCache.leftPushAll(key, shopTypeJsonList);
        stringRedisTemplate.expire(key, RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shopTypes;
    }
}
