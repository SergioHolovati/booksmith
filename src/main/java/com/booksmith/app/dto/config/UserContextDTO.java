package com.booksmith.app.dto.config;

import org.springframework.stereotype.Component;

@Component
public class UserContextDTO {
    private static final ThreadLocal<UserContextDTO> CURRENT = new ThreadLocal<>();

    private String userId;
    private String userName;
    private String requestTraceId;

    public UserContextDTO() {
    }

    public UserContextDTO(String userId, String userName) {
        this.userId = userId;
        this.userName = userName;
    }

    public UserContextDTO(String userId, String userName, String requestTraceId) {
        this.userId = userId;
        this.userName = userName;
        this.requestTraceId = requestTraceId;
    }

    public static UserContextBuilder builder() {
        return new UserContextBuilder();
    }

    public String getUserId() {
        UserContextDTO current = CURRENT.get();
        return current != null ? current.userId : userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        UserContextDTO current = CURRENT.get();
        return current != null ? current.userName : userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getRequestTraceId() {
        UserContextDTO current = CURRENT.get();
        return current != null ? current.requestTraceId : requestTraceId;
    }

    public void setRequestTraceId(String requestTraceId) {
        getOrCreateCurrent().requestTraceId = requestTraceId;
    }

    public void setAuthenticatedUser(UserContextDTO authenticatedUser) {
        UserContextDTO current = getOrCreateCurrent();
        current.userId = authenticatedUser.userId;
        current.userName = authenticatedUser.userName;
    }

    public void clearAuthenticatedUser() {
        UserContextDTO current = CURRENT.get();
        if (current != null) {
            current.userId = null;
            current.userName = null;
        }
    }

    public void clear() {
        CURRENT.remove();
    }

    @Override
    public String toString() {
        return "UserContext{userId='%s', userName='%s', requestTraceId='%s'}"
                .formatted(getUserId(), getUserName(), getRequestTraceId());
    }

    private UserContextDTO getOrCreateCurrent() {
        UserContextDTO current = CURRENT.get();
        if (current == null) {
            current = new UserContextDTO(userId, userName, requestTraceId);
            CURRENT.set(current);
        }
        return current;
    }

    public static class UserContextBuilder {
        private String userId;
        private String userName;
        private String requestTraceId;

        public UserContextBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public UserContextBuilder userName(String userName) {
            this.userName = userName;
            return this;
        }

        public UserContextBuilder requestTraceId(String requestTraceId) {
            this.requestTraceId = requestTraceId;
            return this;
        }

        public UserContextDTO build() {
            return new UserContextDTO(userId, userName, requestTraceId);
        }
    }
}
