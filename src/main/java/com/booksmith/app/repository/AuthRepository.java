package com.booksmith.app.repository;

import com.booksmith.app.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthRepository extends JpaRepository<User,String> {
    User findByEmail(String email);
}
