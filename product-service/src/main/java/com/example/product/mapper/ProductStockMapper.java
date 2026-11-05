package com.example.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.common.entity.ProductStock;
import org.apache.ibatis.annotations.Mapper;

/**
 * 商品库存 Mapper
 */
@Mapper
public interface ProductStockMapper extends BaseMapper<ProductStock> {
}
