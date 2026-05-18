package com.booksmith.app.dto.config;

import org.springframework.stereotype.Component;

@Component
public class UserContext {
    private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

    private String userId;
    private String userName;
    private String requestTraceId;

    public UserContext() {
    }

    public UserContext(String userId, String userName) {
        this.userId = userId;
        this.userName = userName;
    }

    public UserContext(String userId, String userName, String requestTraceId) {
        this.userId = userId;
        this.userName = userName;
        this.requestTraceId = requestTraceId;
    }

    public static UserContextBuilder builder() {
        return new UserContextBuilder();
    }

    public String getUserId() {
        UserContext current = CURRENT.get();
        return current != null ? current.userId : userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        UserContext current = CURRENT.get();
        return current != null ? current.userName : userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getRequestTraceId() {
        UserContext current = CURRENT.get();
        return current != null ? current.requestTraceId : requestTraceId;
    }

    public void setRequestTraceId(String requestTraceId) {
        getOrCreateCurrent().requestTraceId = requestTraceId;
    }

    public void setAuthenticatedUser(UserContext authenticatedUser) {
        UserContext current = getOrCreateCurrent();
        current.userId = authenticatedUser.userId;
        current.userName = authenticatedUser.userName;
    }

    public void clearAuthenticatedUser() {
        UserContext current = CURRENT.get();
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

    private UserContext getOrCreateCurrent() {
        UserContext current = CURRENT.get();
        if (current == null) {
            current = new UserContext(userId, userName, requestTraceId);
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

        public UserContext build() {
            return new UserContext(userId, userName, requestTraceId);
        }
    }
}
