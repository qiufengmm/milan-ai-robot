package com.milanai.robot.service;


import com.milanai.robot.model.dto.SearchResultDTO;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public interface SearchResultContentFetcherService {


    /**
     * 并发批量获取搜索结果页面的内容
     *
     * @param searchResults
     * @param timeout
     * @param unit
     * @return
     */
    CompletableFuture<List<SearchResultDTO>> batchFetch(List<SearchResultDTO> searchResults,
                                                        long timeout,
                                                        TimeUnit unit);
}
