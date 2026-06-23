package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.PurchaseReturnQueryDTO;
import org.example.back.dto.PurchaseReturnSaveDTO;
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
import org.example.back.vo.PurchaseReturnVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PurchaseReturnService {

    @Autowired
    private BizPurchaseReturnMapper bizPurchaseReturnMapper;

    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BaseSupplierMapper baseSupplierMapper;

    @Autowired
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    private void requirePurchaseReturnModuleAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_PURCHASE, "仅采购部门管理员可访问进货退货模块");
    }

    public PageResult<PurchaseReturnVO> page(PurchaseReturnQueryDTO queryDTO) {
        requirePurchaseReturnModuleAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();
        // 构建查询条件
        LambdaQueryWrapper<BizPurchaseReturn> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getReturnNo()), BizPurchaseReturn::getReturnNo, queryDTO.getReturnNo())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizPurchaseReturn::getGoodsName, queryDTO.getGoodsName())
                .eq(queryDTO.getGoodsId() != null, BizPurchaseReturn::getGoodsId, queryDTO.getGoodsId())
            .ge(startTime != null, BizPurchaseReturn::getOperationTime, startTime)
            .lt(endTime != null, BizPurchaseReturn::getOperationTime, endTime)
                .orderByDesc(BizPurchaseReturn::getId);
        // 执行分页查询
        Page<BizPurchaseReturn> page = bizPurchaseReturnMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BaseGoods> goodsMap = buildGoodsMap(page.getRecords().stream().map(BizPurchaseReturn::getGoodsId).collect(Collectors.toSet()));
        Map<Long, BaseSupplier> supplierMap = buildSupplierMap(goodsMap.values().stream()
                .map(BaseGoods::getSupplierId)
                .filter(id -> id != null)
                .collect(Collectors.toSet()));
        Map<Long, BizApprovalOrder> approvalMap = buildLatestApprovalMap(page.getRecords().stream().map(BizPurchaseReturn::getId).toList());
        // 将查询到的 BizPurchaseReturn 实体列表转换为 PurchaseReturnVO 列表，并关联查询商品和供应商信息以填充 VO 对象
        List<PurchaseReturnVO> records = page.getRecords().stream()
                .map(item -> {
                    BaseGoods goods = goodsMap.get(item.getGoodsId());
                    BaseSupplier supplier = goods == null ? null : supplierMap.get(goods.getSupplierId());
                return toVO(item, supplier, approvalMap.get(item.getId()));
                })
                .toList();
        // 构建并返回分页结果对象，包含转换后的 VO 列表和分页信息
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public PurchaseReturnVO getById(Long id) {
        requirePurchaseReturnModuleAccess();
        BizPurchaseReturn entity = requireEntity(id);
        BaseGoods goods = baseGoodsMapper.selectById(entity.getGoodsId());
        BaseSupplier supplier = goods == null ? null : baseSupplierMapper.selectById(goods.getSupplierId());
        return toVO(entity, supplier, resolveLatestApproval(entity.getId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(PurchaseReturnSaveDTO dto) {
        requirePurchaseReturnModuleAccess();
        validateQuantity(dto.getQuantity());

        BizPurchase sourcePurchase = requireSourcePurchase(dto.getSourcePurchaseId());
        ensureSourcePurchaseNormal(sourcePurchase);
        validateReturnableQuantity(sourcePurchase, dto.getQuantity());

        BaseGoods goods = requireGoods(sourcePurchase.getGoodsId());
        BigDecimal unitPrice = resolveUnitPrice(dto.getUnitPrice(), sourcePurchase.getUnitPrice(), "来源进货单缺少单价，请传入退货单价");
        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        // 构建 BizPurchaseReturn 实体对象，复制属性并设置关联信息
        BizPurchaseReturn entity = new BizPurchaseReturn();
        entity.setReturnNo(CodeGenerator.purchaseReturnNo());
        entity.setSourcePurchaseId(sourcePurchase.getId());
        entity.setSourcePurchaseNo(sourcePurchase.getPurchaseNo());
        entity.setGoodsId(goods.getId());
        entity.setGoodsName(goods.getGoodsName());
        entity.setQuantity(dto.getQuantity());
        entity.setUnitPrice(unitPrice);
        entity.setTotalPrice(unitPrice.multiply(BigDecimal.valueOf(dto.getQuantity())));
        entity.setOperatorId(loginUser.getId());
        entity.setOperatorName(loginUser.getRealName());
        entity.setOperationTime(operationTime);
        entity.setRemark(dto.getRemark());
        entity.setBizStatus(1);

        bizPurchaseReturnMapper.insert(entity);
        decreaseStock(goods.getId(), dto.getQuantity(), "库存不足，无法执行商品退货");
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePurchaseReturnModuleAccess();
        BizPurchaseReturn entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "进货退货单");
        validateDeleteWindow(entity.getOperationTime(), "进货退货单");
        increaseStock(entity.getGoodsId(), entity.getQuantity());
        bizPurchaseReturnMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        requirePurchaseReturnVoidExecutionAccess();
        BizPurchaseReturn entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "进货退货单");

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchaseReturn> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizPurchaseReturn::getId, entity.getId())
                .eq(BizPurchaseReturn::getBizStatus, 1)
                .set(BizPurchaseReturn::getBizStatus, 2)
                .set(BizPurchaseReturn::getVoidTime, now)
                .set(BizPurchaseReturn::getVoidReason, reason);
        int rows = bizPurchaseReturnMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("进货退货单已被处理，禁止重复作废");
        }

        increaseStock(entity.getGoodsId(), entity.getQuantity());
        // 如果前端请求中包含 createRedFlush 标志且为 true，则创建对应的红冲单
        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
            BizPurchaseReturn redFlushDoc = new BizPurchaseReturn();
            redFlushDoc.setReturnNo(CodeGenerator.purchaseReturnNo());
            redFlushDoc.setSourcePurchaseId(entity.getSourcePurchaseId());
            redFlushDoc.setSourcePurchaseNo(entity.getSourcePurchaseNo());
            redFlushDoc.setGoodsId(entity.getGoodsId());
            redFlushDoc.setGoodsName(entity.getGoodsName());
            redFlushDoc.setQuantity(-entity.getQuantity());
            redFlushDoc.setUnitPrice(entity.getUnitPrice());
            redFlushDoc.setTotalPrice(entity.getTotalPrice().negate());
            redFlushDoc.setOperatorId(loginUser.getId());
            redFlushDoc.setOperatorName(loginUser.getRealName());
            redFlushDoc.setOperationTime(now);
            redFlushDoc.setRemark("红冲来源:" + entity.getReturnNo());
            redFlushDoc.setBizStatus(3);
            redFlushDoc.setSourceId(entity.getId());
            redFlushDoc.setVoidReason(reason);
            bizPurchaseReturnMapper.insert(redFlushDoc);
        }
    }

    private void requirePurchaseReturnVoidExecutionAccess() {
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)) {
            return;
        }
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_PURCHASE)) {
            throw BusinessException.validateFail("历史进货退货单作废/红冲需提交仓储审批");
        }
        throw BusinessException.forbidden("仅采购部门管理员可发起进货退货作废申请，且需由仓储部门审批");
    }

    private BizPurchaseReturn requireEntity(Long id) {
        BizPurchaseReturn entity = bizPurchaseReturnMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.notFound("进货退货单不存在");
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

    private BizPurchase requireSourcePurchase(Long sourcePurchaseId) {
        BizPurchase purchase = bizPurchaseMapper.selectById(sourcePurchaseId);
        if (purchase == null) {
            throw BusinessException.validateFail("来源进货单不存在");
        }
        return purchase;
    }

    private void ensureSourcePurchaseNormal(BizPurchase sourcePurchase) {
        if (sourcePurchase.getBizStatus() == null || sourcePurchase.getBizStatus() != 1) {
            throw BusinessException.validateFail("来源进货单非正常状态，禁止退货");
        }
    }
    // 验证退货数量是否在来源进货单的可退范围内，考虑已关联的退货单数量
    private void validateReturnableQuantity(BizPurchase sourcePurchase, Integer returnQty) {
        LambdaQueryWrapper<BizPurchaseReturn> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizPurchaseReturn::getSourcePurchaseId, sourcePurchase.getId())
                .eq(BizPurchaseReturn::getBizStatus, 1);
        List<BizPurchaseReturn> linkedReturns = bizPurchaseReturnMapper.selectList(wrapper);
        int returnedQty = linkedReturns.stream().map(BizPurchaseReturn::getQuantity).reduce(0, Integer::sum);
        int availableQty = sourcePurchase.getQuantity() - returnedQty;
        if (availableQty <= 0) {
            throw BusinessException.validateFail("来源进货单已无可退数量");
        }
        if (returnQty > availableQty) {
            throw BusinessException.validateFail("退货数量超出可退数量，当前最多可退: " + availableQty);
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
    // 确保单据处于正常状态
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
        wrapper.eq(BizApprovalOrder::getBizType, "purchase_return")
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

    private PurchaseReturnVO toVO(BizPurchaseReturn entity, BaseSupplier supplier, BizApprovalOrder approvalOrder) {
        PurchaseReturnVO vo = new PurchaseReturnVO();
        BeanUtils.copyProperties(entity, vo);
        LocalDateTime bizTime = entity.getOperationTime() == null ? entity.getCreateTime() : entity.getOperationTime();
        vo.setSupplierName(supplier == null ? null : supplier.getSupplierName());
        vo.setSourcePurchaseId(entity.getSourcePurchaseId());
        vo.setSourcePurchaseNo(entity.getSourcePurchaseNo());
        vo.setOrderNo(entity.getSourcePurchaseNo());
        vo.setReturnQuantity(entity.getQuantity());
        vo.setReturnAmount(entity.getTotalPrice());
        vo.setOperationTime(bizTime);
        vo.setReturnDate(bizTime);
        vo.setOperator(entity.getOperatorName());
        vo.setReason(entity.getRemark());
        vo.setBizStatus(entity.getBizStatus());
        vo.setSourceId(entity.getSourceId());
        vo.setVoidTime(entity.getVoidTime());
        vo.setVoidReason(entity.getVoidReason());
        vo.setApprovalStatus(approvalOrder == null ? null : approvalOrder.getStatus());
        vo.setApprovalRequestAction(approvalOrder == null ? null : approvalOrder.getRequestAction());
        return vo;
    }
}
