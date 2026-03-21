package com.authx.authservice.service;

import com.authx.authservice.entity.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class LoginAlertService {

    private final MailService mailService;
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public LoginAlertService(MailService mailService, RedisTemplate<String, Object> redisTemplate) {
        this.mailService = mailService;
        this.redisTemplate = redisTemplate;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Checks if this login is anomalous asynchronously.
     * Uses Redis Sets for O(1) detection instead of MySQL.
     */
    @Async
    public void checkAnomalousLogin(User user, String ipAddress, String userAgent) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        String deviceSignature = ipAddress + ":" + parseDevice(userAgent);
        String redisKey = "known_devices:" + user.getId();
        
        // Add returns true (1L) if it's a new element, false (0L) if already exists
        Long added = redisTemplate.opsForSet().add(redisKey, deviceSignature);
        boolean isNewDeviceOrIp = (added != null && added > 0);

        if (isNewDeviceOrIp) {
            String location = fetchLocationFromIp(ipAddress);
            if (location != null) {
                mailService.sendNewDeviceAlert(user.getEmail(), location, parseDevice(userAgent), LocalDateTime.now());
            } else {
                mailService.sendNewDeviceAlert(user.getEmail(), "Unknown Location (IP: " + ipAddress + ")", parseDevice(userAgent), LocalDateTime.now());
            }
        }
    }

    private String fetchLocationFromIp(String ipAddress) {
        try {
            // Using a free IP geolocation API
            // Note: in production, an API key or local GeoIP database is recommended.
            String url = "http://ip-api.com/json/" + ipAddress;
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && "success".equals(response.get("status"))) {
                String city = (String) response.get("city");
                String country = (String) response.get("country");
                return city + ", " + country;
            }
        } catch (Exception e) {
            // Ignore failure, we'll fall back to unknown location
        }
        return null;
    }

    private String parseDevice(String ua) {
        if (ua == null) return "Unknown device";
        String lower = ua.toLowerCase();
        if (lower.contains("android")) return "Android Device";
        if (lower.contains("iphone") || lower.contains("ipad") || lower.contains("ios")) return "iOS Device";
        if (lower.contains("macintosh") || lower.contains("mac os")) return "Mac";
        if (lower.contains("windows")) return "Windows PC";
        if (lower.contains("linux")) return "Linux PC";
        
        if (lower.contains("chrome")) return "Chrome Browser";
        if (lower.contains("firefox")) return "Firefox Browser";
        if (lower.contains("safari")) return "Safari Browser";
        return ua.length() > 40 ? ua.substring(0, 40) + "..." : ua;
    }
}
