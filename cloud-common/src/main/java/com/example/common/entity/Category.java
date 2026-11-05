package com.example.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品分类实体(对应 t_category 表)
 * 注意: children 字段不是数据库列, 用 @TableField(exist=false) 告诉 MP 不要映射
 */
@Data
@NoArgsConstructor
@TableName("t_category")
public class Category {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private String name;
    private Integer sort;
    private String icon;
    private Integer status;
    @TableLogic
    private Integer isDeleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    // 子分类列表(非数据库字段, 用于前端树形展示)
    @TableField(exist = false)
    private List<Category> children;
}
