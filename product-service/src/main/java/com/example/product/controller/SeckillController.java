package com.example.product.controller;

import com.example.common.dto.SeckillConfirmRequest;
import com.example.common.result.Result;
import com.example.product.service.SeckillService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * 秒杀控制器(薄控制器): 只做参数校验 + Result 封装, 业务在 SeckillService。
 */
@RestController
@RequestMapping("/seckill")
public class SeckillController {

    @Resource
    private SeckillService seckillService;

    /** 开卖前预热库存(可手动调用或开卖时触发) */
    @PostMapping("/prepare/{id}")
    public Result<Boolean> prepare(@PathVariable Long id) {
        return Result.success(seckillService.prepare(id));
    }

    /** 查询剩余库存(前端展示) */
    @GetMapping("/stock/{id}")
    public Result<Long> remaining(@PathVariable Long id) {
        return Result.success(seckillService.remaining(id));
    }

    /** 秒杀下单前确认: DB 锁定库存 + 一人一单幂等(供 order-service Feign 调用) */
    @PostMapping("/confirm")
    public Result<Boolean> confirm(@RequestBody SeckillConfirmRequest req) {
        if (req == null || req.getUserId() == null || req.getProductId() == null
                || req.getCount() == null || req.getCount() <= 0) {
            return Result.fail(400, "秒杀确认参数不合法");
        }
        return Result.success(seckillService.confirmOrBind(req));
    }

    /** 秒杀扣减: 只见 Redis, 抢到返回 success(data=true) */
    @PostMapping("/buy/{id}")
    public Result<Boolean> buy(@PathVariable Long id,
                               @RequestParam(defaultValue = "1") Integer count) {
        if (count == null || count <= 0) {
            return Result.fail(400, "数量不合法");
        }
        return Result.success(seckillService.seckill(id, count));
    }
}