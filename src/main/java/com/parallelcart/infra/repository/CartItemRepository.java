package com.parallelcart.infra.repository;

import com.parallelcart.domain.model.Cart;
import com.parallelcart.domain.model.CartItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByCart(Cart cart);
}
