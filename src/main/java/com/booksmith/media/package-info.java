/**
 * Armazenamento de imagens (capas, avatares e, no futuro, a biblioteca de ilustrações).
 * Próximo passo (§11 da arquitetura): upload direto ao S3 por URL pré-assinada + worker de processamento.
 */
@ApplicationModule(displayName = "Mídia", allowedDependencies = "common")
package com.booksmith.media;

import org.springframework.modulith.ApplicationModule;
