package com.example.seckill.product.controller;

import com.example.seckill.product.domain.Product;
import com.example.seckill.product.dto.UpdateProductRequest;
import com.example.seckill.product.search.ElasticsearchProductSearchService;
import com.example.seckill.product.service.ProductService;
import com.example.seckill.user.controller.UserController.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final ElasticsearchProductSearchService searchService;

    public ProductController(ProductService productService, ElasticsearchProductSearchService searchService) {
        this.productService = productService;
        this.searchService = searchService;
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> getProduct(@PathVariable Long id) {
        Product product = productService.getProductById(id);
        if (product == null) {
            return ApiResponse.fail("商品不存在");
        }
        return ApiResponse.success(product);
    }

    @GetMapping("/list")
    public ApiResponse<List<Product>> listProducts() {
        return ApiResponse.success(productService.listAllProducts());
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> updateProduct(@PathVariable Long id,
                                              @Valid @RequestBody UpdateProductRequest request) {
        Product product = new Product();
        product.setId(id);
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        product.setStatus(request.getStatus());
        return ApiResponse.success(productService.updateProduct(product));
    }

    @DeleteMapping("/{id}/cache")
    public ApiResponse<Void> evictProductCache(@PathVariable Long id) {
        productService.evictProductCache(id);
        return ApiResponse.success();
    }

    @GetMapping("/search")
    public ApiResponse<ElasticsearchProductSearchService.SearchResponse> search(@RequestParam("q") String q,
                                                                               @RequestParam(value = "size", defaultValue = "10") int size) {
        try {
            return ApiResponse.success(searchService.search(q, size));
        } catch (Exception e) {
            return ApiResponse.fail("搜索不可用：" + e.getMessage());
        }
    }

    @GetMapping("/search/reindex")
    public ApiResponse<Void> reindex() {
        try {
            searchService.reindexAll();
            return ApiResponse.success();
        } catch (Exception e) {
            return ApiResponse.fail("重建索引失败：" + e.getMessage());
        }
    }
}
