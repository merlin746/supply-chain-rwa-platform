package com.rwa.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data
@TableName("sys_user")
public class SysUser {
 @TableId(type=IdType.AUTO) private Long id;
 private String username;
 private String password;
 private String realName;
 private String role;
 private Long enterpriseId;
 private String walletAddress;
 private Integer status;
 private LocalDateTime createdAt;
 private LocalDateTime updatedAt;
}
