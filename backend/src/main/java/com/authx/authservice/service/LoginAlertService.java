package com.authx.authservice.service;

import com.authx.authservice.entity.AuditAction;
import com.authx.authservice.entity.AuditLog;
import com.authx.authservice.entity.User;
import com.authx.authservice.repository.AuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class LoginAlertService {

    private final AuditLogRepository auditLogRepository;
    private final MailService mailService;
    private final RestTemplate restTemplate;

    @Autowired
    public LoginAlertService(AuditLogRepository auditLogRepository, MailService mailService) {
        this.auditLogRepository = auditLogRepository;
        this.mailService = mailService;
        this.restTemplate = new RestTemplate();
    }

    /**
     * Checks if this login is anomalous asynchronously.
     * If anomalous, sends an alert email.
     */
    @Async
    public void checkAnomalousLogin(User user, String ipAddress, String userAgent) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        // Get recent successful logins for this user
        List<AuditLog> previousLogins = auditLogRepository.findTop10ByEmailAndActionOrderByCreatedAtDesc(
                user.getEmail(), AuditAction.LOGIN_SUCCESS);

        boolean isNewDeviceOrIp = true;
        
        // We skip the very first one if it's the exact current login just inserted, 
        // but checking 10 is usually enough to find a match if it's a returning device.
        for (AuditLog log : previousLogins) {
            // Give some buffer for the current login just being recorded
            if (log.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(10))) {
                continue; 
            }
            if (ipAddress.equals(log.getIpAddress()) && userAgent.equals(log.getUserAgent())) {
                isNewDeviceOrIp = false;
                break;
            }
        }

        if (isNewDeviceOrIp) {
            // It strongly appears to be a new IP/Device combination.
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
