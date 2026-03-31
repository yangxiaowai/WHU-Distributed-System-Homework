package com.example.seckill.order.mapper;

import com.example.seckill.order.domain.SeckillOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SeckillOrderMapper {

    @Insert("INSERT INTO seckill_order(id, order_no, user_id, product_id, amount, status) " +
            "VALUES(#{id}, #{orderNo}, #{userId}, #{productId}, #{amount}, #{status})")
    int insert(SeckillOrder order);

    @Select("SELECT * FROM seckill_order WHERE id = #{id}")
    SeckillOrder findById(Long id);

    @Select("SELECT * FROM seckill_order WHERE user_id = #{userId} AND product_id = #{productId} LIMIT 1")
    SeckillOrder findByUserIdAndProductId(@Param("userId") Long userId, @Param("productId") Long productId);

    @Select("SELECT * FROM seckill_order WHERE user_id = #{userId} ORDER BY update_time DESC, id DESC")
    List<SeckillOrder> findByUserId(Long userId);
}
