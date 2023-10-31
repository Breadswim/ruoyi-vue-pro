package cn.iocoder.yudao.module.crm.framework.core.aop;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.crm.dal.dataobject.permission.CrmPermissionDO;
import cn.iocoder.yudao.module.crm.framework.core.annotations.CrmPermission;
import cn.iocoder.yudao.module.crm.service.permission.CrmPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.lang.reflect.Field;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString;
import static cn.iocoder.yudao.module.crm.enums.ErrorCodeConstants.CRM_PERMISSION_DENIED;
import static cn.iocoder.yudao.module.crm.framework.enums.CrmPermissionLevelEnum.*;

/**
 * Crm 数据权限校验 AOP 切面
 *
 * @author HUIHUI
 */
@Component
@Aspect
@Slf4j
public class CrmPermissionAspect {

    @Resource
    private CrmPermissionService crmPermissionService;

    /**
     * 获得用户编号
     *
     * @return 用户编号
     */
    private static Long getUserId() {
        return WebFrameworkUtils.getLoginUserId();
    }

    private Long getBizId(JoinPoint joinPoint, CrmPermission crmPermission) throws NoSuchFieldException, IllegalAccessException {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            if (ObjUtil.notEqual(arg.getClass().getName(), crmPermission.getIdFor()[0].getName())) {
                continue;
            }
            // 使用反射获取id属性
            Field idValue = arg.getClass().getDeclaredField("id");
            // 设置字段为可访问
            idValue.setAccessible(true);
            return (Long) idValue.get(arg);
        }
        return null;
    }

    @Before("@annotation(crmPermission)")
    public void doBefore(JoinPoint joinPoint, CrmPermission crmPermission) {
        try {
            Long bizId = crmPermission.getIdFor().length > 0 ? getBizId(joinPoint, crmPermission) : (Long) joinPoint.getArgs()[0]; // 获取操作数据的编号
            Integer bizType = crmPermission.bizType().getType(); // 模块类型
            Integer permissionLevel = crmPermission.permissionLevel().getLevel(); // 需要的权限级别

            // TODO 如果是超级管理员则直接通过
            //if (superAdmin){
            //    return;
            //}

            // 1. 获取数据权限
            List<CrmPermissionDO> bizPermissions = crmPermissionService.getPermissionByBizTypeAndBizId(bizType, bizId);
            CrmPermissionDO userPermission = CollUtil.findOne(bizPermissions, item -> ObjUtil.equal(item.getUserId(), getUserId()));
            if (isOwner(userPermission.getPermissionLevel())) { // 校验自己是否是负责人
                return;
            }
            if (isRead(permissionLevel)) { // 读权限
                // 如果没有数据权限或没有负责人则表示此记录为公海数据所有人都有只读权限
                if (CollUtil.isEmpty(bizPermissions) || !CollUtil.anyMatch(bizPermissions, item -> isOwner(item.getPermissionLevel()))) {
                    return;
                }
                if (isRead(userPermission.getPermissionLevel())) { // 校验当前用户是否有读权限
                    return;
                }
                //如果查询数据的话拥有写权限的也能查询
                if (isWrite(userPermission.getPermissionLevel())) { // 校验当前用户是否有写权限
                    return;
                }
            }
            if (isWrite(permissionLevel)) { // 写权限
                if (isWrite(userPermission.getPermissionLevel())) { // 校验当前用户是否有写权限
                    return;
                }
            }

            // 2. 没通过结束，报错 {}操作失败，原因：没有权限
            throw exception(CRM_PERMISSION_DENIED, crmPermission.bizType().getName());
        } catch (Exception ex) {
            log.error("[doBefore][crmPermission({}) 数据校验错误]", toJsonString(crmPermission), ex);
            // TODO 报错抛个什么异常好呢
            throw exception(CRM_PERMISSION_DENIED, crmPermission.bizType().getName());
        }
    }

}