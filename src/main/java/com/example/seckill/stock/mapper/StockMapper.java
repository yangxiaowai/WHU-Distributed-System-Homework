package com.example.seckill.stock.mapper;

import com.example.seckill.stock.domain.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface StockMapper {

    @Select("SELECT * FROM stock WHERE product_id = #{productId}")
    Stock findByProductId(Long productId);

    @Select("SELECT * FROM stock ORDER BY product_id ASC")
    List<Stock> listAll();

    @Update("UPDATE stock SET available = available - #{amount}, version = version + 1, " +
            "update_time = CURRENT_TIMESTAMP WHERE product_id = #{productId} AND available >= #{amount}")
    int deductAvailable(@Param("productId") Long productId, @Param("amount") Integer amount);
}
