package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.Mention;
import qg.qgent.dto.MessageListResponse;
import qg.qgent.dto.MessageResponse;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.MessageEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.MessageMapper;
import qg.qgent.mapper.RequirementGroupMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 群消息业务：发送（类型校验、clientMessageId 幂等、sequence 单调递增）与游标分页拉取（契约 §7）。
 */
@Service
public class MessageService {
    private static final Set<String> PUBLIC_TYPES = Set.of("TEXT", "CODE", "IMAGE", "FILE", "QUOTE");
    private static final String CURSOR_PREFIX = "cursor_";

    private final MessageMapper messageMapper;
    private final RequirementGroupMapper groupMapper;
    private final ProjectAccessService access;
    private final ObjectMapper mapper;

    public MessageService(MessageMapper messageMapper, RequirementGroupMapper groupMapper,
            ProjectAccessService access, ObjectMapper mapper) {
        this.messageMapper = messageMapper;
        this.groupMapper = groupMapper;
        this.access = access;
        this.mapper = mapper;
    }

    /**
     * 发送消息。
     * <p>
     * 发送前持有群行锁（FOR UPDATE）以保证 sequence 单调递增；client_message_id 在同一群内唯一，
     * 断线重试或并发撞唯一键时返回原消息（幂等）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param body      发送请求
     * @return 消息视图
     */
    @Transactional
    public MessageResponse send(UUID actor, UUID projectId, UUID groupId, MessageSendRequest body) {
        access.requireMember(projectId, actor);
        RequirementGroupEntity group = groupMapper.selectOne(Wrappers.<RequirementGroupEntity>lambdaQuery()
                .eq(RequirementGroupEntity::getId, groupId)
                .last("FOR UPDATE"));
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }

        String type = normalizeType(body.getType());
        validateContent(type, body.getContent());
        List<Mention> mentions = body.getMentions() == null ? List.of() : body.getMentions();
        String clientMessageId = trimmed(body.getClientMessageId());

        // clientMessageId 幂等：断线重试命中直接返回原消息（优先于其余字段校验）
        if (clientMessageId != null) {
            MessageEntity existing = findByClientMessageId(groupId, clientMessageId);
            if (existing != null) {
                return toResponse(existing);
            }
        }
        if (body.getReplyToId() != null) {
            MessageEntity reply = messageMapper.selectById(body.getReplyToId());
            if (reply == null || !reply.getRequirementGroupId().equals(groupId)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLY_MESSAGE_NOT_FOUND",
                        "被回复的消息不存在");
            }
        }

        MessageEntity message = new MessageEntity();
        message.setId(UuidV7.next());
        message.setRequirementGroupId(groupId);
        message.setSequenceNo(messageMapper.nextSequence(groupId));
        message.setAuthorUserId(actor);
        message.setClientMessageId(clientMessageId);
        message.setMessageType(type);
        message.setContent(writeJson(body.getContent()));
        message.setMentions(writeJson(mentions));
        message.setReplyToMessageId(body.getReplyToId());
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException e) {
            // 并发下 clientMessageId 撞唯一键：返回已存在的原消息
            if (clientMessageId != null) {
                MessageEntity existing = findByClientMessageId(groupId, clientMessageId);
                if (existing != null) {
                    return toResponse(existing);
                }
            }
            throw new ApiException(HttpStatus.CONFLICT, "MESSAGE_SEND_CONFLICT", "消息发送冲突，请重试");
        }
        groupMapper.update(null, Wrappers.<RequirementGroupEntity>lambdaUpdate()
                .set(RequirementGroupEntity::getLastMessageAt, LocalDateTime.now(ZoneOffset.UTC))
                .eq(RequirementGroupEntity::getId, groupId));
        return toResponse(messageMapper.selectById(message.getId()));
    }

    /**
     * 游标拉取群消息，新消息在前；limit 默认 30、最大 100。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param cursor    上一页游标，可为空
     * @param limit     每页数量（自动收敛到 1..100）
     * @return 消息分页结果
     */
    public MessageListResponse list(UUID actor, UUID projectId, UUID groupId, String cursor, int limit) {
        access.requireMember(projectId, actor);
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        Long cursorSequence = decodeCursor(cursor);
        int pageSize = Math.min(Math.max(limit, 1), 100);
        List<MessageEntity> rows = messageMapper.selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequirementGroupId, groupId)
                .lt(cursorSequence != null, MessageEntity::getSequenceNo, cursorSequence)
                .orderByDesc(MessageEntity::getSequenceNo)
                .last("limit " + (pageSize + 1)));
        boolean hasMore = rows.size() > pageSize;
        List<MessageResponse> views = rows.stream().limit(pageSize).map(this::toResponse).toList();
        String nextCursor = hasMore && !views.isEmpty()
                ? encodeCursor(views.get(views.size() - 1).getSequence())
                : null;
        return new MessageListResponse(views, nextCursor, hasMore);
    }

    private MessageEntity findByClientMessageId(UUID groupId, String clientMessageId) {
        return messageMapper.selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getRequirementGroupId, groupId)
                .eq(MessageEntity::getClientMessageId, clientMessageId));
    }

    private String normalizeType(String type) {
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        if (!PUBLIC_TYPES.contains(normalized)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID", "不支持的消息类型");
        }
        return normalized;
    }

    private void validateContent(String type, Map<String, Object> content) {
        if (content == null || content.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_CONTENT_INVALID", "消息内容不能为空");
        }
        switch (type) {
            case "TEXT", "QUOTE" -> requireField(content, "text", "文本消息缺少 text 字段");
            case "CODE" -> {
                requireField(content, "language", "代码消息缺少 language 字段");
                requireField(content, "code", "代码消息缺少 code 字段");
            }
            case "IMAGE", "FILE" -> requireField(content, "url", type + " 消息缺少 url 字段");
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_TYPE_INVALID",
                    "不支持的消息类型");
        }
    }

    private void requireField(Map<String, Object> content, String field, String message) {
        Object value = content.get(field);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MESSAGE_CONTENT_INVALID", message);
        }
    }

    private MessageResponse toResponse(MessageEntity m) {
        Map<String, Object> content = readJson(m.getContent(), new TypeReference<Map<String, Object>>() {
        });
        List<Mention> mentions = m.getMentions() == null || m.getMentions().isBlank()
                ? List.of()
                : readJson(m.getMentions(), new TypeReference<List<Mention>>() {
                });
        String senderType = m.getAuthorUserId() == null ? "SYSTEM" : "USER";
        return new MessageResponse(m.getId().toString(), m.getRequirementGroupId().toString(), m.getSequenceNo(),
                m.getMessageType(), content, m.getAuthorUserId() == null ? null : m.getAuthorUserId().toString(),
                senderType, m.getReplyToMessageId() == null ? null : m.getReplyToMessageId().toString(), mentions,
                m.getCreatedAt());
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("消息内容序列化失败", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("消息内容解析失败", e);
        }
    }

    private String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private String encodeCursor(Long sequence) {
        byte[] raw = Long.toString(sequence).getBytes(StandardCharsets.UTF_8);
        return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private Long decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            if (!cursor.startsWith(CURSOR_PREFIX)) {
                throw new IllegalArgumentException();
            }
            byte[] raw = Base64.getUrlDecoder().decode(cursor.substring(CURSOR_PREFIX.length()));
            return Long.parseLong(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标无效");
        }
    }
}
