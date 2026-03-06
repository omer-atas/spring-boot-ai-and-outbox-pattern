package com.springboot.outbox.domain.enums;

public enum EventStatus {
    PENDING, // Henüz işlenmedi
    PROCESSING, // Şu anda işleniyor
    PROCESSED, // Başarıyla işlendi
    FAILED, // İşlem başarısız
    DEAD_LETTER // Maksimum retry aşıldı
}
