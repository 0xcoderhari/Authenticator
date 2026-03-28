package com.authx.authservice.service;

import com.authx.authservice.entity.User;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAlertService {

    private final MailService mailService;
    private final EmailQueueProducer emailQueueProducer;
    private final RestTemplate restTemplate;
    private final Map<Long, Set<String>> knownDevices = new ConcurrentHashMap<>();

    public LoginAlertService(MailService mailService, EmailQueueProducer emailQueueProducer) {
        this.mailService = mailService;
        this.emailQueueProducer = emailQueueProducer;
        this.restTemplate = new RestTemplate();
    }

    @Async
    public void checkAnomalousLogin(User user, String ipAddress, String userAgent) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        String deviceSignature = ipAddress + ":" + parseDevice(userAgent);
        Set<String> devices = knownDevices.computeIfAbsent(user.getId(), k -> ConcurrentHashMap.newKeySet());
        boolean isNewDeviceOrIp = devices.add(deviceSignature);

        if (isNewDeviceOrIp) {
            String location = fetchLocationFromIp(ipAddress);
            String device = parseDevice(userAgent);
            LocalDateTime time = LocalDateTime.now();
            
            if (location != null) {
                mailService.queueNewDeviceAlert(user.getEmail(), location, device, time, emailQueueProducer);
            } else {
                mailService.queueNewDeviceAlert(user.getEmail(), "Unknown Location (IP: " + ipAddress + ")", device, time, emailQueueProducer);
            }
        }
    }

    private String fetchLocationFromIp(String ipAddress) {
        try {
            String url = "http://ip-api.com/json/" + ipAddress;
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response != null && "success".equals(response.get("status"))) {
                String city = (String) response.get("city");
                String country = (String) response.get("country");
                return city + ", " + country;
            }
        } catch (Exception e) {
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