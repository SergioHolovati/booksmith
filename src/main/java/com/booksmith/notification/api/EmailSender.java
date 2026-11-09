package com.booksmith.notification.api;

public interface EmailSender {

    void sendEmailVerification(String to, String code);

    void sendPasswordReset(String to, String resetLink);
}
