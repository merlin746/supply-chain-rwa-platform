package com.rwa.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("blockchain_event")
public class BlockchainEvent {
 @TableId(type=IdType.AUTO) private Long id;
 private String txHash;
 private Long logIndex;
 private Long blockNumber;
 private String contractAddress;
 private String topic0;
 private String eventName;
 private String rawTopics;
 private String rawData;
 private LocalDateTime syncedAt;
}
