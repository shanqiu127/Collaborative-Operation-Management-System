package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.PurchaseQueryDTO;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizPurchase;
import org.example.back.entity.BizPurchaseReturn;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseReturnMapper;
import org.example.back.vo.PurchaseSourceOptionVO;
import org.example.back.vo.PurchaseVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PurchaseService {

    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BaseSupplierMapper baseSupplierMapper;

    @Autowired
    private BizPurchaseReturnMapper bizPurchaseReturnMapper;

    @Autowired
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    private void requirePurchaseModuleAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_PURCHASE, "仅采购部门管理员可访问进货模块");
    }

    public PageResult<PurchaseVO> page(PurchaseQueryDTO queryDTO) {
        requirePurchaseModuleAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<BizPurchase> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getPurchaseNo()), BizPurchase::getPurchaseNo, queryDTO.getPurchaseNo())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizPurchase::getGoodsName, queryDTO.getGoodsName())
                .eq(queryDTO.getGoodsId() != null, BizPurchase::getGoodsId, queryDTO.getGoodsId())
            .ge(startTime != null, BizPurchase::getOperationTime, startTime)
            .lt(endTime != null, BizPurchase::getOperationTime, endTime)
                .orderByDesc(BizPurchase::getId);

        Page<BizPurchase> page = bizPurchaseMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BaseGoods> goodsMap = buildGoodsMap(page.getRecords().stream().map(BizPurchase::getGoodsId).collect(Collectors.toSet()));
        Map<Long, BaseSupplier> supplierMap = buildSupplierMap(goodsMap.values().stream()
                .map(BaseGoods::getSupplierId)
                .filter(id -> id != null)
                .collect(Collectors.toSet()));
        Map<Long, BizApprovalOrder> approvalMap = buildLatestApprovalMap(page.getRecords().stream().map(BizPurchase::getId).toList());

        List<PurchaseVO> records = page.getRecords().stream()
                .map(item -> {
                    BaseGoods goods = goodsMap.get(item.getGoodsId());
                    BaseSupplier supplier = goods == null ? null : supplierMap.get(goods.getSupplierId());
                return toVO(item, supplier, approvalMap.get(item.getId()));
                })
                .toList();

        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public PurchaseVO getById(Long id) {
        requirePurchaseModuleAccess();
        BizPurchase purchase = requirePurchase(id);
        BaseGoods goods = baseGoodsMapper.selectById(purchase.getGoodsId());
        BaseSupplier supplier = goods == null ? null : baseSupplierMapper.selectById(goods.getSupplierId());
        return toVO(purchase, supplier, resolveLatestApproval(purchase.getId()));
    }

    public List<PurchaseSourceOptionVO> returnableOptions(Long goodsId) {
        requirePurchaseModuleAccess();
        LambdaQueryWrapper<BizPurchase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizPurchase::getBizStatus, 1)
                .eq(goodsId != null, BizPurchase::getGoodsId, goodsId)
                .orderByDesc(BizPurchase::getOperationTime)
                .orderByDesc(BizPurchase::getId);
        List<BizPurchase> purchases = bizPurchaseMapper.selectList(wrapper);
        if (purchases.isEmpty()) {
            return List.of();
        }

        List<Long> purchaseIds = purchases.stream().map(BizPurchase::getId).toList();
        LambdaQueryWrapper<BizPurchaseReturn> returnWrapper = new LambdaQueryWrapper<>();
        returnWrapper.in(BizPurchaseReturn::getSourcePurchaseId, purchaseIds)
                .eq(BizPurchaseReturn::getBizStatus, 1);
        List<BizPurchaseReturn> linkedReturns = bizPurchaseReturnMapper.selectList(returnWrapper);

        Map<Long, Integer> returnedMap = new HashMap<>();
        for (BizPurchaseReturn item : linkedReturns) {
            returnedMap.merge(item.getSourcePurchaseId(), item.getQuantity(), Integer::sum);
        }

        return purchases.stream()
                .map(item -> {
                    int returnedQty = returnedMap.getOrDefault(item.getId(), 0);
                    int returnableQty = item.getQuantity() - returnedQty;
                    if (returnableQty <= 0) {
                        return null;
                    }
                    PurchaseSourceOptionVO vo = new PurchaseSourceOptionVO();
                    vo.setId(item.getId());
                    vo.setPurchaseNo(item.getPurchaseNo());
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
    public void create(PurchaseSaveDTO dto) {
        requirePurchaseModuleAccess();
        validateQuantity(dto.getQuantity());

        BaseGoods goods = requireGoods(dto.getGoodsId());
        ensureGoodsEnabled(goods);
        BigDecimal unitPrice = resolveUnitPrice(dto.getUnitPrice(), goods.getPurchasePrice(), "商品进价为空，请传入进货单价");
        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        BizPurchase purchase = new BizPurchase();
        purchase.setPurchaseNo(CodeGenerator.purchaseNo());
        purchase.setGoodsId(goods.getId());
        purchase.setGoodsName(goods.getGoodsName());
        purchase.setQuantity(dto.getQuantity());
        purchase.setUnitPrice(unitPrice);
        purchase.setTotalPrice(unitPrice.multiply(BigDecimal.valueOf(dto.getQuantity())));
        purchase.setOperatorId(loginUser.getId());
        purchase.setOperatorName(loginUser.getRealName());
        purchase.setOperationTime(operationTime);
        purchase.setRemark(dto.getRemark());
        purchase.setBizStatus(1);

        bizPurchaseMapper.insert(purchase);
        increaseStock(goods.getId(), dto.getQuantity());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePurchaseModuleAccess();
        BizPurchase purchase = requirePurchase(id);
        ensureNormalStatus(purchase.getBizStatus(), "进货单");
        validateDeleteWindow(purchase.getOperationTime(), "进货单");
        decreaseStock(purchase.getGoodsId(), purchase.getQuantity(), "当前库存不足，无法删除该进货单");
        bizPurchaseMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        requirePurchaseVoidExecutionAccess();
        BizPurchase purchase = requirePurchase(id);
        ensureNormalStatus(purchase.getBizStatus(), "进货单");

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchase> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizPurchase::getId, purchase.getId())
                .eq(BizPurchase::getBizStatus, 1)
                .set(BizPurchase::getBizStatus, 2)
                .set(BizPurchase::getVoidTime, now)
                .set(BizPurchase::getVoidReason, reason);
        int rows = bizPurchaseMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("进货单已被处理，禁止重复作废");
        }

        decreaseStock(purchase.getGoodsId(), purchase.getQuantity(), "当前库存不足，无法作废该进货单");

        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
            BizPurchase redFlushDoc = new BizPurchase();
            redFlushDoc.setPurchaseNo(CodeGenerator.purchaseNo());
            redFlushDoc.setGoodsId(purchase.getGoodsId());
            redFlushDoc.setGoodsName(purchase.getGoodsName());
            redFlushDoc.setQuantity(-purchase.getQuantity());
            redFlushDoc.setUnitPrice(purchase.getUnitPrice());
            redFlushDoc.setTotalPrice(purchase.getTotalPrice().negate());
            redFlushDoc.setOperatorId(loginUser.getId());
            redFlushDoc.setOperatorName(loginUser.getRealName());
            redFlushDoc.setOperationTime(now);
            redFlushDoc.setRemark("红冲来源:" + purchase.getPurchaseNo());
            redFlushDoc.setBizStatus(3);
            redFlushDoc.setSourceId(purchase.getId());
            redFlushDoc.setVoidReason(reason);
            bizPurchaseMapper.insert(redFlushDoc);
        }
    }

    private void requirePurchaseVoidExecutionAccess() {
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)) {
            return;
        }
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_PURCHASE)) {
            throw BusinessException.validateFail("历史进货单作废/红冲需提交仓储审批");
        }
        throw BusinessException.forbidden("仅采购部门管理员可发起进货作废申请，且需由仓储部门审批");
    }

    private BizPurchase requirePurchase(Long id) {
        BizPurchase purchase = bizPurchaseMapper.selectById(id);
        if (purchase == null) {
            throw BusinessException.notFound("进货单不存在");
        }
        return purchase;
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

    private Map<Long, BaseGoods> buildGoodsMap(Set<Long> goodsIds) {
        if (goodsIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BaseGoods::getId, goodsIds);
        return baseGoodsMapper.selectList(wrapper).stream().collect(Collectors.toMap(BaseGoods::getId, Function.identity()));
    }

    private Map<Long, BaseSupplier> buildSupplierMap(Set<Long> supplierIds) {
        if (supplierIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BaseSupplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BaseSupplier::getId, supplierIds);
        return baseSupplierMapper.selectList(wrapper).stream().collect(Collectors.toMap(BaseSupplier::getId, Function.identity()));
    }

    private Map<Long, BizApprovalOrder> buildLatestApprovalMap(List<Long> bizIds) {
        if (bizIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "purchase")
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

    private PurchaseVO toVO(BizPurchase purchase, BaseSupplier supplier, BizApprovalOrder approvalOrder) {
        PurchaseVO vo = new PurchaseVO();
        BeanUtils.copyProperties(purchase, vo);
        LocalDateTime bizTime = purchase.getOperationTime() == null ? purchase.getCreateTime() : purchase.getOperationTime();
        vo.setOrderNo(purchase.getPurchaseNo());
        vo.setSupplierName(supplier == null ? null : supplier.getSupplierName());
        vo.setPrice(purchase.getUnitPrice());
        vo.setTotalAmount(purchase.getTotalPrice());
        vo.setOperationTime(bizTime);
        vo.setPurchaseDate(bizTime);
        vo.setOperator(purchase.getOperatorName());
        vo.setBizStatus(purchase.getBizStatus());
        vo.setSourceId(purchase.getSourceId());
        vo.setVoidTime(purchase.getVoidTime());
        vo.setVoidReason(purchase.getVoidReason());
        vo.setApprovalStatus(approvalOrder == null ? null : approvalOrder.getStatus());
        vo.setApprovalRequestAction(approvalOrder == null ? null : approvalOrder.getRequestAction());
        return vo;
    }
}
