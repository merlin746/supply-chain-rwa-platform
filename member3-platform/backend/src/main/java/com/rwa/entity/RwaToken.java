package com.rwa.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
@Data
@TableName("rwa_token")
public class RwaToken {
 @TableId(type=IdType.AUTO) private Long id;
 private String tokenId;
 private String slotId;
 private String parentTokenId;
 private String ownerAddress;
 private Long enterpriseId;
 private Long invoiceId;
 private BigDecimal valueAmount;
 private Long dueTimestamp;
 private String status;
 private String contractAddress;
 private String txHash;
 private Long blockNumber;
 private String metadataJson;
 private LocalDateTime createdAt;
 private LocalDateTime updatedAt;
}
