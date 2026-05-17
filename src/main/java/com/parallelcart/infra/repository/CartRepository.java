package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Cart;
import com.parallelcart.domain.model.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartRepository extends JpaRepository<Cart, Long> {
    Optional<Cart> findByUser(User user);
}
