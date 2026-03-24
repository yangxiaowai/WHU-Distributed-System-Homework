package com.example.seckill.product.mapper;

import com.example.seckill.product.domain.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProductMapper {

    @Select("SELECT * FROM product WHERE id = #{id}")
    Product findById(Long id);

    @Select("SELECT * FROM product ORDER BY id ASC")
    List<Product> listAll();

    @Update("UPDATE product SET name = #{name}, description = #{description}, price = #{price}, " +
            "status = #{status}, update_time = CURRENT_TIMESTAMP WHERE id = #{id}")
    int updateById(Product product);
}
