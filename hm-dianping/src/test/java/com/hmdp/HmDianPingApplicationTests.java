package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Test
    void testRedisIdWoker() throws InterruptedException {
        CountDownLatch latch=new CountDownLatch(300);

        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = "+id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }

        latch.await();
        long end=System.currentTimeMillis();
        System.out.println("time ="+(end-begin));
    }

    @Test
    void testSaveShop(){
        //shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void testCacheClient(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY+1L,shop,10L,TimeUnit.SECONDS);
    }
    @Test
    void testThread(){
        new Thread(()-> System.out.println(Thread.currentThread().getName())).start();
        new Thread(()-> System.out.println(Thread.currentThread().getName())).start();
        new Thread(()-> System.out.println(Thread.currentThread().getName())).start();
        new Thread(()-> System.out.println(Thread.currentThread().getName())).start();
    }

    @Test
    void loadShopData(){
        // 1.查询店铺消息
        List<Shop> shopList=shopService.list();
        // 2.把店铺分组,按照typeId分组,typeId一致的放到一个集合
        Map<Long,List<Shop>> map=shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()){
            Long typeId = entry.getKey();
            String key="shop:geo:"+typeId;

            List<Shop> shop = entry.getValue();

            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(shop.size()); //一组GeoLocation集合
            for (Shop shop1 : shop) { //将shop位置信息装入GeoLocation集合中
                //stringRedisTemplate.opsForGeo().add(key,new Point(shop1.getX(),shop1.getY()),shop1.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop1.getId().toString(),
                        new Point(shop1.getX(),shop1.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,locations); //将shop位置一次性全部导入Redis
        }



        // 4.
    }

    @Test
    void testMapSet(){
        // 从Map到Set的转换
        Map<String, Integer> fruitMap = new HashMap<>();
        fruitMap.put("Apple", 1);
        fruitMap.put("Banana", 2);
        fruitMap.put("Orange", 3);

        // 获取键的Set
        Set<String> keySet = fruitMap.keySet();
        System.out.println("Keys: " + keySet);

        // 获取值的Collection(可以转为Set)
        Collection<Integer> values = fruitMap.values();
        Set<Integer> valueSet = new HashSet<>(values);
        System.out.println("Values: " + valueSet);

        // 获取Entry的Set
        Set<Map.Entry<String, Integer>> entrySet = fruitMap.entrySet();
        System.out.println("Entries: " + entrySet);

        // 从Set到Map的转换
        Set<String> fruits = new HashSet<>(Arrays.asList("Apple", "Banana", "Orange"));
        Map<String, Integer> newMap = new HashMap<>();
        int index = 1;
        for (String fruit : fruits) {
            newMap.put(fruit, index++);
        }
        System.out.println("Map from Set: " + newMap);

        // 使用Stream API进行转换
        Map<String, Integer> streamMap = fruits.stream()
                .collect(Collectors.toMap(
                        fruit -> fruit,     // key mapper
                        fruit -> fruits.size() - fruit.length()  // value mapper
                ));
        System.out.println("Stream Map: " + streamMap);
    }
}
