package com.example.ai.feign;

import com.example.common.entity.Category;
import com.example.common.entity.Product;
import com.example.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * product-service Feign 客户端(商品查询类工具的后端实现)。
 * 返回类型与 ProductController 的接口签名保持一致。
 */
@FeignClient("product-service")
public interface ProductClient {

    /** 商品列表(分类/关键词/分页/排序; 注意 list 接口无价格区间参数) */
    @GetMapping("/product/list")
    Result<Result.PageData<Product>> list(@RequestParam("categoryId") Long categoryId,
                                          @RequestParam("keyword") String keyword,
                                          @RequestParam("page") Integer page,
                                          @RequestParam("size") Integer size,
                                          @RequestParam("sort") String sort);

    /** 商品详情(仅"上架且过审"商品可见, 否则 code=404) */
    @GetMapping("/product/{id}")
    Result<Product> getById(@PathVariable("id") Long id);

    /** 分类树 */
    @GetMapping("/product/categories")
    Result<List<Category>> categories();
}
