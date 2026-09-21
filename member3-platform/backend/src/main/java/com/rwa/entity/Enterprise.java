package com.rwa.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
@TableName("enterprise")
public class Enterprise {
 @TableId(type=IdType.AUTO) private Long id;
 private String name;
 private String enterpriseCode;
 private String enterpriseType;
 private BigDecimal creditLimit;
 private BigDecimal creditUsed;
 private String walletAddress;
 private LocalDateTime createdAt;
 private LocalDateTime updatedAt;
}
