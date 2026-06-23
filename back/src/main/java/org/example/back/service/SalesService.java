package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.SalesQueryDTO;
import org.example.back.dto.SalesSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesReturn;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.mapper.BizSalesReturnMapper;
import org.example.back.vo.SalesSourceOptionVO;
import org.example.back.vo.SalesVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesService {

    @Autowired
    private BizSalesMapper bizSalesMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BizSalesReturnMapper bizSalesReturnMapper;

    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    @Autowired
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    private void requireSalesModuleAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_SALES, "仅销售部门管理员可访问销售模块");
    }

    public PageResult<SalesVO> page(SalesQueryDTO queryDTO) {
        requireSalesModuleAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getSalesNo()), BizSales::getSalesNo, queryDTO.getSalesNo())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizSales::getGoodsName, queryDTO.getGoodsName())
                .eq(queryDTO.getGoodsId() != null, BizSales::getGoodsId, queryDTO.getGoodsId())
            .ge(startTime != null, BizSales::getOperationTime, startTime)
            .lt(endTime != null, BizSales::getOperationTime, endTime)
                .orderByDesc(BizSales::getId);

        Page<BizSales> page = bizSalesMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BizApprovalOrder> approvalMap = buildLatestApprovalMap(page.getRecords().stream().map(BizSales::getId).toList());
        List<SalesVO> records = page.getRecords().stream().map(item -> toVO(item, approvalMap.get(item.getId()))).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public SalesVO getById(Long id) {
        requireSalesModuleAccess();
        BizSales entity = requireEntity(id);
        return toVO(entity, resolveLatestApproval(entity.getId()));
    }

    public List<SalesSourceOptionVO> returnableOptions(Long goodsId) {
        requireSalesModuleAccess();
        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSales::getBizStatus, 1)
                .eq(goodsId != null, BizSales::getGoodsId, goodsId)
                .orderByDesc(BizSales::getOperationTime)
                .orderByDesc(BizSales::getId);
        List<BizSales> salesList = bizSalesMapper.selectList(wrapper);
        if (salesList.isEmpty()) {
            return List.of();
        }

        List<Long> salesIds = salesList.stream().map(BizSales::getId).toList();
        LambdaQueryWrapper<BizSalesReturn> returnWrapper = new LambdaQueryWrapper<>();
        returnWrapper.in(BizSalesReturn::getSourceSalesId, salesIds)
                .eq(BizSalesReturn::getBizStatus, 1);
        List<BizSalesReturn> linkedReturns = bizSalesReturnMapper.selectList(returnWrapper);

        Map<Long, Integer> returnedMap = new HashMap<>();
        for (BizSalesReturn item : linkedReturns) {
            returnedMap.merge(item.getSourceSalesId(), item.getQuantity(), Integer::sum);
        }

        return salesList.stream()
                .map(item -> {
                    int returnedQty = returnedMap.getOrDefault(item.getId(), 0);
                    int returnableQty = item.getQuantity() - returnedQty;
                    if (returnableQty <= 0) {
                        return null;
                    }
                    SalesSourceOptionVO vo = new SalesSourceOptionVO();
                    vo.setId(item.getId());
                    vo.setSalesNo(item.getSalesNo());
                    vo.setGoodsId(item.getGoodsId());
                    vo.setGoodsName(item.getGoodsName());
                    vo.setQuantity(item.getQuantity());
                    vo.setReturnedQuantity(returnedQty);
                    vo.setReturnableQuantity(returnableQty);
                    vo.setUnitPrice(item.getUnitPrice());
                    vo.setOperationTime(item.getOperationTime());
                    return vo;
                })
                .filter(item -> item != null)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(SalesSaveDTO dto) {
        requireSalesModuleAccess();
        validateQuantity(dto.getQuantity());

        BaseGoods goods = requireGoods(dto.getGoodsId());
        ensureGoodsEnabled(goods);
        BigDecimal unitPrice = resolveUnitPrice(dto.getUnitPrice(), goods.getSalePrice(), "商品售价为空，请传入销售单价");
        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();
        CostSnapshot costSnapshot = buildSalesCostSnapshot(goods.getId(), operationTime, goods.getPurchasePrice());

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        BizSales entity = new BizSales();
        entity.setSalesNo(CodeGenerator.salesNo());
        entity.setGoodsId(goods.getId());
        entity.setGoodsName(goods.getGoodsName());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitPrice(unitPrice);
        entity.setCostUnitPrice(costSnapshot.unitPrice());
        entity.setCostTotalPrice(costSnapshot.unitPrice().multiply(BigDecimal.valueOf(dto.getQuantity())));
        entity.setCostSource(costSnapshot.source());
        entity.setTotalPrice(unitPrice.multiply(BigDecimal.valueOf(dto.getQuantity())));
        entity.setOperatorId(loginUser.getId());
        entity.setOperatorName(loginUser.getRealName());
        entity.setOperationTime(operationTime);
        entity.setRemark(dto.getRemark());
        entity.setBizStatus(1);

        bizSalesMapper.insert(entity);
        decreaseStock(goods.getId(), dto.getQuantity(), "库存不足，销售出库失败");
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireSalesModuleAccess();
        BizSales entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "销售单");
        validateDeleteWindow(entity.getOperationTime(), "销售单");
        increaseStock(entity.getGoodsId(), entity.getQuantity());
        bizSalesMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        requireSalesVoidExecutionAccess();
        BizSales entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "销售单");

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizSales> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizSales::getId, entity.getId())
                .eq(BizSales::getBizStatus, 1)
                .set(BizSales::getBizStatus, 2)
                .set(BizSales::getVoidTime, now)
                .set(BizSales::getVoidReason, reason);
        int rows = bizSalesMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("销售单已被处理，禁止重复作废");
        }

        increaseStock(entity.getGoodsId(), entity.getQuantity());

        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
            BizSales redFlushDoc = new BizSales();
            redFlushDoc.setSalesNo(CodeGenerator.salesNo());
            redFlushDoc.setGoodsId(entity.getGoodsId());
            redFlushDoc.setGoodsName(entity.getGoodsName());
            redFlushDoc.setQuantity(-entity.getQuantity());
            redFlushDoc.setUnitPrice(entity.getUnitPrice());
            redFlushDoc.setCostUnitPrice(entity.getCostUnitPrice());
            redFlushDoc.setCostTotalPrice(entity.getCostTotalPrice() == null ? null : entity.getCostTotalPrice().negate());
            redFlushDoc.setCostSource(entity.getCostSource());
            redFlushDoc.setTotalPrice(entity.getTotalPrice().negate());
            redFlushDoc.setOperatorId(loginUser.getId());
            redFlushDoc.setOperatorName(loginUser.getRealName());
            redFlushDoc.setOperationTime(now);
            redFlushDoc.setRemark("红冲来源:" + entity.getSalesNo());
            redFlushDoc.setBizStatus(3);
            redFlushDoc.setSourceId(entity.getId());
            redFlushDoc.setVoidReason(reason);
            bizSalesMapper.insert(redFlushDoc);
        }
    }

    private void requireSalesVoidExecutionAccess() {
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)) {
            return;
        }
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_SALES)) {
            throw BusinessException.validateFail("历史销售单作废/红冲需提交仓储审批");
        }
        throw BusinessException.forbidden("仅销售部门管理员可发起销售作废申请，且需由仓储部门审批");
    }

    private BizSales requireEntity(Long id) {
        BizSales entity = bizSalesMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.notFound("销售单不存在");
        }
        return entity;
    }

    private BaseGoods requireGoods(Long goodsId) {
        BaseGoods goods = baseGoodsMapper.selectById(goodsId);
        if (goods == null) {
            throw BusinessException.validateFail("商品不存在");
        }
        return goods;
    }

    private void ensureGoodsEnabled(BaseGoods goods) {
        if (goods.getStatus() == null || goods.getStatus() != 1) {
            throw BusinessException.validateFail("商品已下架，无法创建业务单据");
        }
    }

    private void validateDeleteWindow(LocalDateTime operationTime, String docName) {
        if (operationTime == null) {
            return;
        }
        if (!operationTime.toLocalDate().equals(LocalDate.now())) {
            throw BusinessException.validateFail("仅允许删除当天" + docName + "，历史单据请走作废/红冲流程");
        }
    }

    private void ensureNormalStatus(Integer bizStatus, String docName) {
        if (bizStatus == null || bizStatus == 1) {
            return;
        }
        if (bizStatus == 2) {
            throw BusinessException.validateFail(docName + "已作废，禁止重复操作");
        }
        throw BusinessException.validateFail(docName + "为红冲单，禁止删除或再次作废");
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "手工作废";
        }
        return reason.trim();
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw BusinessException.validateFail("数量必须大于0");
        }
    }

    private BigDecimal resolveUnitPrice(BigDecimal inputPrice, BigDecimal fallbackPrice, String emptyPriceMsg) {
        BigDecimal finalPrice = inputPrice == null ? fallbackPrice : inputPrice;
        if (finalPrice == null) {
            throw BusinessException.validateFail(emptyPriceMsg);
        }
        if (finalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.validateFail("单价必须大于0");
        }
        return finalPrice;
    }

    private CostSnapshot buildSalesCostSnapshot(Long goodsId, LocalDateTime bizTime, BigDecimal fallbackPurchasePrice) {
        BigDecimal purchasePrice = bizPurchaseMapper.latestValidUnitPrice(goodsId, bizTime);
        if (purchasePrice != null && purchasePrice.compareTo(BigDecimal.ZERO) > 0) {
            return new CostSnapshot(purchasePrice, "RECENT_PURCHASE");
        }
        if (fallbackPurchasePrice != null && fallbackPurchasePrice.compareTo(BigDecimal.ZERO) > 0) {
            return new CostSnapshot(fallbackPurchasePrice, "GOODS_PRICE");
        }
        return new CostSnapshot(BigDecimal.ZERO, "ZERO_FALLBACK");
    }

    private void increaseStock(Long goodsId, Integer quantity) {
        LambdaUpdateWrapper<BaseGoods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BaseGoods::getId, goodsId)
                .setSql("stock = stock + {0}", quantity);
        int rows = baseGoodsMapper.update(null, wrapper);
        if (rows == 0) {
            throw BusinessException.validateFail("商品不存在");
        }
    }

    private void decreaseStock(Long goodsId, Integer quantity, String msg) {
        LambdaUpdateWrapper<BaseGoods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BaseGoods::getId, goodsId)
                .ge(BaseGoods::getStock, quantity)
                .setSql("stock = stock - {0}", quantity);
        int rows = baseGoodsMapper.update(null, wrapper);
        if (rows == 0) {
            throw BusinessException.stockInsufficient(msg);
        }
    }

    private Map<Long, BizApprovalOrder> buildLatestApprovalMap(List<Long> bizIds) {
        if (bizIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "sales")
                .in(BizApprovalOrder::getBizId, bizIds)
                .orderByDesc(BizApprovalOrder::getId);
        Map<Long, BizApprovalOrder> approvalMap = new LinkedHashMap<>();
        for (BizApprovalOrder item : bizApprovalOrderMapper.selectList(wrapper)) {
            approvalMap.putIfAbsent(item.getBizId(), item);
        }
        return approvalMap;
    }

    private BizApprovalOrder resolveLatestApproval(Long bizId) {
        return buildLatestApprovalMap(List.of(bizId)).get(bizId);
    }

    private SalesVO toVO(BizSales entity, BizApprovalOrder approvalOrder) {
        SalesVO vo = new SalesVO();
        BeanUtils.copyProperties(entity, vo);
        LocalDateTime bizTime = entity.getOperationTime() == null ? entity.getCreateTime() : entity.getOperationTime();
        vo.setSalesPrice(entity.getUnitPrice());
        vo.setTotalAmount(entity.getTotalPrice());
        vo.setOperationTime(bizTime);
        vo.setSalesDate(bizTime);
        vo.setOperator(entity.getOperatorName());
        vo.setBizStatus(entity.getBizStatus());
        vo.setSourceId(entity.getSourceId());
        vo.setVoidTime(entity.getVoidTime());
        vo.setVoidReason(entity.getVoidReason());
        vo.setApprovalStatus(approvalOrder == null ? null : approvalOrder.getStatus());
        vo.setApprovalRequestAction(approvalOrder == null ? null : approvalOrder.getRequestAction());
        return vo;
    }

    private record CostSnapshot(BigDecimal unitPrice, String source) {
    }
}
