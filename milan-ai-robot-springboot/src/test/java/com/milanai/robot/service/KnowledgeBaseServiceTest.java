package com.milanai.robot.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.milanai.robot.domain.dos.KnowledgeBaseDO;
import com.milanai.robot.domain.mapper.KnowledgeBaseMapper;
import com.milanai.robot.enums.ResponseCodeEnum;
import com.milanai.robot.exception.BizException;
import com.milanai.robot.model.vo.customerService.KnowledgeBaseReqVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeBaseServiceTest {
    private final KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeBaseService service = new KnowledgeBaseService(mapper);

    @BeforeAll
    static void registerEntityMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "knowledge-tests"), KnowledgeBaseDO.class);
    }

    @Test
    void omittedLibraryIsRejectedBeforeAnyDefaultLookup() {
        assertThatThrownBy(() -> service.require(null)).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode());
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void invalidIdsFailBeforeLookingUpAnyLibrary(long id) {
        assertThatThrownBy(() -> service.require(id)).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void missingLibraryHasSpecificBusinessErrorRatherThanDefaultFallback() {
        assertThatThrownBy(() -> service.require(7L)).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ResponseCodeEnum.KNOWLEDGE_BASE_NOT_FOUND.getErrorCode());
        verify(mapper).selectById(7L);
        verify(mapper, never()).selectById(1L);
    }

    @Test
    void createTrimsContentAndReturnsGeneratedLibraryId() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        when(mapper.insert(any(KnowledgeBaseDO.class))).thenAnswer(call -> {
            KnowledgeBaseDO row = call.getArgument(0);
            row.setId(12L);
            return 1;
        });

        assertThat(service.create(request("  员工服务  ", "  入职考勤与账号申请  "))).isEqualTo(12L);

        ArgumentCaptor<KnowledgeBaseDO> saved = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("员工服务");
        assertThat(saved.getValue().getDescription()).isEqualTo("入职考勤与账号申请");
        assertThat(saved.getValue().getCreateTime()).isAfterOrEqualTo(before);
        assertThat(saved.getValue().getUpdateTime()).isAfterOrEqualTo(before);
    }

    @Test
    void nullDescriptionIsStoredAsEmptyText() {
        when(mapper.insert(any(KnowledgeBaseDO.class))).thenAnswer(call -> {
            ((KnowledgeBaseDO) call.getArgument(0)).setId(12L);
            return 1;
        });
        service.create(request("IT支持", null));

        ArgumentCaptor<KnowledgeBaseDO> saved = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getDescription()).isEmpty();
    }

    @Test
    void malformedCreateRequestsDoNotReachPersistence() {
        for (KnowledgeBaseReqVO request : new KnowledgeBaseReqVO[] {
                null, request(null, ""), request(" \t\n", ""), request("x".repeat(101), ""),
                request("支持", "x".repeat(501))}) {
            assertThatThrownBy(() -> service.create(request)).isInstanceOf(BizException.class)
                    .extracting("errorCode").isEqualTo(ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode());
        }
        verifyNoInteractions(mapper);
    }

    @Test
    void updateTrimsEditableFieldsAndPreservesTheTargetIdentity() {
        when(mapper.selectById(7L)).thenReturn(KnowledgeBaseDO.builder().id(7L).name("旧名").build());

        service.update(7L, request("  新名 ", " 新说明  "));

        ArgumentCaptor<KnowledgeBaseDO> saved = ArgumentCaptor.forClass(KnowledgeBaseDO.class);
        verify(mapper).updateById(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(7L);
        assertThat(saved.getValue().getName()).isEqualTo("新名");
        assertThat(saved.getValue().getDescription()).isEqualTo("新说明");
        assertThat(saved.getValue().getCreateTime()).isNull();
        assertThat(saved.getValue().getUpdateTime()).isNotNull();
    }

    @Test
    void updateWithOmittedIdCannotChangeTheDefaultLibrary() {
        assertThatThrownBy(() -> service.update(null, request("默认库", null)))
                .isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void missingOrInvalidUpdateNeverWritesAPatch() {
        assertThatThrownBy(() -> service.update(7L, request("支持", ""))).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ResponseCodeEnum.KNOWLEDGE_BASE_NOT_FOUND.getErrorCode());
        when(mapper.selectById(8L)).thenReturn(KnowledgeBaseDO.builder().id(8L).build());
        assertThatThrownBy(() -> service.update(8L, request(" ", ""))).isInstanceOf(BizException.class)
                .extracting("errorCode").isEqualTo(ResponseCodeEnum.PARAM_NOT_VALID.getErrorCode());
        verify(mapper, never()).updateById(any(KnowledgeBaseDO.class));
    }


    private KnowledgeBaseReqVO request(String name, String description) {
        KnowledgeBaseReqVO request = new KnowledgeBaseReqVO();
        request.setName(name);
        request.setDescription(description);
        return request;
    }
}
