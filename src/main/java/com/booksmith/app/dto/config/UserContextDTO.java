package com.booksmith.app.dto.config;

import org.springframework.stereotype.Component;

@Component
public class UserContextDTO {
    private static final ThreadLocal<UserContextDTO> CURRENT = new ThreadLocal<>();

    private String userId;
    private String userName;
    private String requestTraceId;
    private String authorId;

    public UserContextDTO() {
    }

    public UserContextDTO(String userId, String userName,String authorId) {
        this.userId = userId;
        this.userName = userName;
        this.authorId = authorId;
    }

    public UserContextDTO(String userId, String userName, String authorId,String requestTraceId) {
        this.userId = userId;
        this.userName = userName;
        this.authorId = authorId;
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


    public String getAuthorId() {
        UserContextDTO current = CURRENT.get();
        return current != null ? current.authorId : authorId;
    }

    public void setAuthorId(String authorId) {
        this.authorId = authorId;
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
        current.authorId = authenticatedUser.authorId;
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
        return "UserContext{userId='%s', userName='%s', requestTraceId='%s', authorId='%s'}"
                .formatted(getUserId(), getUserName(), getRequestTraceId(),getAuthorId());
    }

    private UserContextDTO getOrCreateCurrent() {
        UserContextDTO current = CURRENT.get();
        if (current == null) {
            current = new UserContextDTO(userId, userName, authorId,requestTraceId);
            CURRENT.set(current);
        }
        return current;
    }

    public static class UserContextBuilder {
        private String userId;
        private String userName;
        private String requestTraceId;
        private String authorId;

        public UserContextBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public UserContextBuilder userName(String userName) {
            this.userName = userName;
            return this;
        }

        public UserContextBuilder authorId(String authorId) {
            this.authorId = authorId;
            return this;
        }


        public UserContextBuilder requestTraceId(String requestTraceId) {
            this.requestTraceId = requestTraceId;
            return this;
        }

        public UserContextDTO build() {
            return new UserContextDTO(userId, userName, authorId,requestTraceId);
        }
    }
}
