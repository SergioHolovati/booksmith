package com.booksmith.app.service;

import com.resend.core.exception.ResendException;

public interface EmailService {
    void sendPasswordRecoveryEmail(String destinationEmail,String token) throws ResendException;
    void sendEmailVerificationEmail(String destinationEmail, String token) throws ResendException;
}
