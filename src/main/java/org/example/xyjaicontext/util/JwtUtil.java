package org.example.xyjaicontext.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Map;

@Component
public class JwtUtil {
    //JWT密钥
    @Value("${jwt.secret}")
    private String secret;

    //JWT过期时间
    @Value("${jwt.expiration}")
    private long expiration;

    // 辅助方法：获取密钥对象 (0.12.x 推荐使用 SecretKey 而不是字符串)
    private SecretKey getSigningKey() {
        // 使用 HS256 或 HS512 对应的密钥生成方式
        // 这里简单地将字符串转换为字节，生产环境建议使用更安全的密钥生成方式
        byte[] keyBytes = secret.getBytes();
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username, Map<String, Object> claims) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(username) // 0.12.x 方法名去掉了 "set" 前缀
                .issuedAt(now)
                .expiration(expiryDate) // 0.12.x 方法名变更
                .signWith(getSigningKey()) // 传入 SecretKey 对象
                .compact();
    }

    public String getUsernameFromToken(String token) {
        // 修正核心逻辑：先 build()，再 parse()
        Claims claims = Jwts.parser()
                .verifyWith(getSigningKey()) // 0.12.x 使用 verifyWith 替代 setSigningKey
                .build() // 必须调用 build() 生成解析器
                .parseSignedClaims(token) // 0.12.x 方法名变更为 parseSignedClaims
                .getPayload(); // 0.12.x 获取载荷的方法变更为 getPayload()

        return claims.getSubject();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}