package com.example.stock.facade;

import com.example.stock.repository.RedisLockRepository;
import com.example.stock.service.StockService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class LettuceLockStockFacade {

    private final RedisLockRepository redisLockRepository;
    private final StockService stockService;

    private final RedisTemplate redisTemplate;


    public LettuceLockStockFacade(RedisLockRepository redisLockRepository, StockService stockService, RedisTemplate redisTemplate) {
        this.redisLockRepository = redisLockRepository;
        this.stockService = stockService;
        this.redisTemplate = redisTemplate;
    }

    public void decrease(Long id, Long quantity) throws InterruptedException {
        while (!redisLockRepository.lock(id)){
            Thread.sleep(100);
        }
        try {
            stockService.decrease(id, quantity);
        } finally {
            redisLockRepository.unlock(id);
        }

    }

}
