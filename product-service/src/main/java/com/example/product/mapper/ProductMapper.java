package com.example.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.Product;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品 Mapper
 * 继承 BaseMapper<Product> 后, MyBatis-Plus 自动提供 selectById/insert/updateById/deleteById
 * 等单表 CRUD 方法, 无需写 SQL
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {
}
