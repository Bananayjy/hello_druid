package org.banana.controller;

import org.banana.entity.User;
import org.banana.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @GetMapping
    public Map<String, Object> getAllUsers() {
        List<User> users = userService.findAll();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "获取用户列表成功");
        result.put("data", users);
        result.put("count", users.size());
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> getUserById(@PathVariable Long id) {
        User user = userService.findById(id);
        Map<String, Object> result = new HashMap<>();
        if (user != null) {
            result.put("code", 200);
            result.put("message", "获取用户成功");
            result.put("data", user);
        } else {
            result.put("code", 404);
            result.put("message", "用户不存在");
        }
        return result;
    }

    @GetMapping("/username/{username}")
    public Map<String, Object> getUserByUsername(@PathVariable String username) {
        User user = userService.findByUsername(username);
        Map<String, Object> result = new HashMap<>();
        if (user != null) {
            result.put("code", 200);
            result.put("message", "获取用户成功");
            result.put("data", user);
        } else {
            result.put("code", 404);
            result.put("message", "用户不存在");
        }
        return result;
    }

    @PostMapping
    public Map<String, Object> createUser(@RequestBody User user) {
        boolean saved = userService.save(user);
        Map<String, Object> result = new HashMap<>();
        if (saved) {
            result.put("code", 200);
            result.put("message", "创建用户成功");
            result.put("data", user);
        } else {
            result.put("code", 500);
            result.put("message", "创建用户失败");
        }
        return result;
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateUser(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        boolean updated = userService.update(user);
        Map<String, Object> result = new HashMap<>();
        if (updated) {
            result.put("code", 200);
            result.put("message", "更新用户成功");
            result.put("data", user);
        } else {
            result.put("code", 500);
            result.put("message", "更新用户失败");
        }
        return result;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> deleteUser(@PathVariable Long id) {
        boolean removed = userService.deleteById(id);
        Map<String, Object> result = new HashMap<>();
        if (removed) {
            result.put("code", 200);
            result.put("message", "删除用户成功");
        } else {
            result.put("code", 500);
            result.put("message", "删除用户失败");
        }
        return result;
    }

    @GetMapping("/count")
    public Map<String, Object> getUserCount() {
        long count = userService.count();
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("message", "统计用户数量成功");
        result.put("data", count);
        return result;
    }
}