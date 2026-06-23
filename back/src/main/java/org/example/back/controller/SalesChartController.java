package org.example.back.controller;

import org.example.back.common.annotation.RequireAdmin;
import org.example.back.common.result.Result;
import org.example.back.dto.SalesChartQueryDTO;
import org.example.back.service.SalesChartService;
import org.example.back.vo.BrandRatioItemVO;
import org.example.back.vo.ChartOverviewVO;
import org.example.back.vo.ProfitBrandTopVO;
import org.example.back.vo.ProfitOverviewVO;
import org.example.back.vo.ProfitTrendVO;
import org.example.back.vo.SalesTop5VO;
import org.example.back.vo.SalesTrendVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/business/charts")
@RequireAdmin("仅管理员可访问销售图表模块")
public class SalesChartController {

    @Autowired
    private SalesChartService salesChartService;

    @GetMapping("/overview")
    public Result<ChartOverviewVO> overview(SalesChartQueryDTO queryDTO) {
        return Result.success(salesChartService.getOverview(queryDTO));
    }

    @GetMapping("/top5")
    public Result<SalesTop5VO> top5(SalesChartQueryDTO queryDTO) {
        return Result.success(salesChartService.getTop5(queryDTO));
    }

    @GetMapping("/brand-ratio")
    public Result<List<BrandRatioItemVO>> brandRatio(SalesChartQueryDTO queryDTO) {
        return Result.success(salesChartService.getBrandRatio(queryDTO));
    }

    @GetMapping("/daily-trend")
    public Result<SalesTrendVO> dailyTrend(SalesChartQueryDTO queryDTO) {
        return Result.success(salesChartService.getDailyTrend(queryDTO));
    }

    @GetMapping("/profit-overview")
    public Result<ProfitOverviewVO> profitOverview(SalesChartQueryDTO queryDTO) {
        return Result.success(salesChartService.getProfitOverview(queryDTO));
    }

    @GetMapping("/profit-brand-top")
    public Result<ProfitBrandTopVO> profitBrandTop(SalesChartQueryDTO queryDTO) {
        return Result.success(salesChartService.getProfitBrandTop(queryDTO));
    }

    @GetMapping("/profit-daily-trend")
    public Result<ProfitTrendVO> profitDailyTrend(SalesChartQueryDTO queryDTO) {
        return Result.success(salesChartService.getProfitDailyTrend(queryDTO));
    }
}
