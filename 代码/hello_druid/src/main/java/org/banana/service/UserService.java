package org.banana.service;

import org.banana.entity.User;
import org.banana.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public List<User> findAll() {
        return userRepository.findAll();
    }

    public User findById(Long id) {
        return userRepository.findById(id);
    }

    public User findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public boolean save(User user) {
        return userRepository.save(user) > 0;
    }

    public boolean update(User user) {
        return userRepository.update(user) > 0;
    }

    public boolean deleteById(Long id) {
        return userRepository.deleteById(id) > 0;
    }

    public long count() {
        return userRepository.count();
    }
}