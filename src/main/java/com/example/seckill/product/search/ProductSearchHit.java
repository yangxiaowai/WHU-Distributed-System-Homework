package com.example.seckill.product.search;

import com.example.seckill.product.domain.Product;

public record ProductSearchHit(Product product, double score) {}

