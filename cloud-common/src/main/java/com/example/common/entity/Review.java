package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 订单评价实体(对应 t_review 表)
 *
 * 一个订单明细(order_item_id)只允许一条评价, 由唯一索引兜底;
 * 只有订单状态=4(已完成)且属于当前用户时才能评价。
 */
@Data
@NoArgsConstructor
@TableName("t_review")
public class Review {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderItemId;
    private Long orderId;
    private Long productId;
    private Long userId;
    private Long merchantId;
    /** 评分 1-5 星 */
    private Integer rating;
    /** 评价内容, 最多 500 字 */
    private String content;
    /** 评价图片 URL, 逗号分隔, 最多 3 张 */
    private String images;
    /** 是否匿名: 1-匿名 0-实名 */
    private Boolean anonymous;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createTime;
}
