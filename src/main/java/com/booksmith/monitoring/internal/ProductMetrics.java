package com.booksmith.monitoring.internal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Métricas de produto do backoffice (plano de produto, Fase 0): se os escritores voltam, o que usam e quanto a IA
 * custa. Só contagens agregadas, lidas direto das tabelas dos outros módulos (somente leitura, nunca o conteúdo dos
 * livros). É a exceção documentada às fronteiras dos módulos: um relatório que cruza tudo não tem dono natural.
 *
 * <p>"Ativo" = escreveu alguma palavra no dia ({@code manuscript.writing_days}), que é o que importa no produto;
 * só entrar no app não conta.
 */
@Component
public class ProductMetrics {

    /** Semanas da série (a atual incluída). */
    public static final int WEEKS = 8;

    private final JdbcClient jdbc;

    ProductMetrics(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Uma semana (começa na segunda): quem escreveu, quanto, capítulos novos e contas novas. */
    public record Week(LocalDate week, long writers, long words, long chapters, long signups) {
    }

    /** Contas: total, com e-mail confirmado, e quem escreveu nos últimos 7 e 30 dias. */
    public record Accounts(long total, long verified, long active7, long active30) {
    }

    /**
     * Retenção: das contas com idade para isso, quantas escreveram de novo na semana 1 (dias 7 a 13 depois do
     * cadastro) e na semana 4 (dias 28 a 34).
     */
    public record Retention(long week1Cohort, long week1Back, long week4Cohort, long week4Back) {
    }

    /** Ativação: das contas com pelo menos 7 dias, quantas fizeram cada coisa na primeira semana. */
    public record Activation(long cohort, long wrote, long book, long character, long continuityAlert) {
    }

    /** Quantas contas usaram cada parte do produto nos últimos 30 dias. */
    public record Usage(String feature, long users) {
    }

    /** Alertas do agente de continuidade por situação: corrigido, ignorado, intencional ou ainda aberto. */
    public record Continuity(long open, long fixed, long ignored, long intentional) {
    }

    /** Uso de IA nos últimos 30 dias; ilustrações com custo do fornecedor (1 crédito = US$ 0,01). */
    public record AiUse(long ideas, long reviewCalls, long illustrations, BigDecimal illustrationCredits) {
    }

    /** Planos dados ou vendidos que valem hoje, por plano e origem (cortesia, fundador). */
    public record PlanCount(String plan, String source, boolean founder, long accounts) {
    }

    public record Report(Accounts accounts, List<Week> weeks, Retention retention, Activation activation,
                         List<Usage> usage, Continuity continuity, AiUse ai, List<PlanCount> plans) {
    }

    @Transactional(readOnly = true)
    public Report report() {
        return new Report(accounts(), weeks(), retention(), activation(), usage(), continuity(), ai(), plans());
    }

    Accounts accounts() {
        return jdbc.sql("""
                        select (select count(*) from identity.users) as total,
                               (select count(*) from identity.users where email_verified) as verified,
                               (select count(distinct owner_id) from manuscript.writing_days
                                 where words > 0 and day > current_date - 7) as active7,
                               (select count(distinct owner_id) from manuscript.writing_days
                                 where words > 0 and day > current_date - 30) as active30
                        """)
                .query((rs, n) -> new Accounts(rs.getLong("total"), rs.getLong("verified"), rs.getLong("active7"),
                        rs.getLong("active30")))
                .single();
    }

    List<Week> weeks() {
        return jdbc.sql("""
                        with weeks as (
                            select generate_series(date_trunc('week', current_date) - make_interval(weeks => :back),
                                                   date_trunc('week', current_date), interval '1 week')::date as week)
                        select w.week,
                               (select count(distinct d.owner_id) from manuscript.writing_days d
                                 where d.words > 0 and d.day >= w.week and d.day < w.week + 7) as writers,
                               (select coalesce(sum(d.words), 0) from manuscript.writing_days d
                                 where d.words > 0 and d.day >= w.week and d.day < w.week + 7) as words,
                               (select count(*) from manuscript.chapters c
                                 where c.deleted_at is null and c.created_at >= w.week and c.created_at < w.week + 7) as chapters,
                               (select count(*) from identity.users u
                                 where u.created_at >= w.week and u.created_at < w.week + 7) as signups
                          from weeks w
                         order by w.week
                        """)
                .param("back", WEEKS - 1)
                .query((rs, n) -> new Week(rs.getObject("week", LocalDate.class), rs.getLong("writers"),
                        rs.getLong("words"), rs.getLong("chapters"), rs.getLong("signups")))
                .list();
    }

    Retention retention() {
        return jdbc.sql("""
                        select count(*) filter (where u.created_at <= now() - interval '14 days') as w1_cohort,
                               count(*) filter (where u.created_at <= now() - interval '14 days' and exists (
                                   select 1 from manuscript.writing_days d where d.owner_id = u.id and d.words > 0
                                      and d.day >= u.created_at::date + 7 and d.day < u.created_at::date + 14)) as w1_back,
                               count(*) filter (where u.created_at <= now() - interval '35 days') as w4_cohort,
                               count(*) filter (where u.created_at <= now() - interval '35 days' and exists (
                                   select 1 from manuscript.writing_days d where d.owner_id = u.id and d.words > 0
                                      and d.day >= u.created_at::date + 28 and d.day < u.created_at::date + 35)) as w4_back
                          from identity.users u
                        """)
                .query((rs, n) -> new Retention(rs.getLong("w1_cohort"), rs.getLong("w1_back"),
                        rs.getLong("w4_cohort"), rs.getLong("w4_back")))
                .single();
    }

    Activation activation() {
        return jdbc.sql("""
                        select count(*) as cohort,
                               count(*) filter (where exists (
                                   select 1 from manuscript.writing_days d where d.owner_id = u.id and d.words > 0
                                      and d.day < u.created_at::date + 7)) as wrote,
                               count(*) filter (where exists (
                                   select 1 from manuscript.books b where b.owner_id = u.id
                                      and b.created_at < u.created_at + interval '7 days')) as book,
                               count(*) filter (where exists (
                                   select 1 from universe.entities e join universe.universes v on v.id = e.universe_id
                                    where v.owner_id = u.id and e.created_at < u.created_at + interval '7 days')) as character,
                               count(*) filter (where exists (
                                   select 1 from continuity.alerts a join manuscript.books b on b.id = a.book_id
                                    where b.owner_id = u.id and a.created_at < u.created_at + interval '7 days')) as alert
                          from identity.users u
                         where u.created_at <= now() - interval '7 days'
                        """)
                .query((rs, n) -> new Activation(rs.getLong("cohort"), rs.getLong("wrote"), rs.getLong("book"),
                        rs.getLong("character"), rs.getLong("alert")))
                .single();
    }

    List<Usage> usage() {
        Map<String, Long> row = jdbc.sql("""
                        select (select count(distinct owner_id) from manuscript.writing_days
                                 where words > 0 and day > current_date - 30) as writing,
                               (select count(distinct v.owner_id) from universe.entities e
                                  join universe.universes v on v.id = e.universe_id
                                 where e.updated_at > now() - interval '30 days') as universe,
                               (select count(distinct b.owner_id) from continuity.alerts a
                                  join manuscript.books b on b.id = a.book_id
                                 where a.created_at > now() - interval '30 days') as continuity,
                               (select count(distinct user_id) from assist.suggestions
                                 where created_at > now() - interval '30 days') as ideas,
                               (select count(distinct user_id) from assist.review_runs
                                 where created_at > now() - interval '30 days') as review,
                               (select count(distinct user_id) from assist.image_generations
                                 where status = 'ready' and created_at > now() - interval '30 days') as illustrations,
                               (select count(distinct owner_id) from manuscript.books
                                 where genre = 'CHILDREN' and deleted_at is null
                                   and updated_at > now() - interval '30 days') as children
                        """)
                .query((rs, n) -> Map.of(
                        "writing", rs.getLong("writing"), "universe", rs.getLong("universe"),
                        "continuity", rs.getLong("continuity"), "ideas", rs.getLong("ideas"),
                        "review", rs.getLong("review"), "illustrations", rs.getLong("illustrations"),
                        "children", rs.getLong("children")))
                .single();
        return List.of("writing", "universe", "continuity", "ideas", "review", "illustrations", "children").stream()
                .map(feature -> new Usage(feature, row.get(feature)))
                .toList();
    }

    Continuity continuity() {
        return jdbc.sql("""
                        select count(*) filter (where status = 'open') as open,
                               count(*) filter (where status = 'fixed') as fixed,
                               count(*) filter (where status = 'ignored') as ignored,
                               count(*) filter (where status = 'intentional') as intentional
                          from continuity.alerts
                        """)
                .query((rs, n) -> new Continuity(rs.getLong("open"), rs.getLong("fixed"), rs.getLong("ignored"),
                        rs.getLong("intentional")))
                .single();
    }

    AiUse ai() {
        return jdbc.sql("""
                        select (select count(*) from assist.suggestions
                                 where created_at > now() - interval '30 days') as ideas,
                               (select coalesce(sum(calls), 0) from assist.review_runs
                                 where created_at > now() - interval '30 days') as review_calls,
                               (select count(*) from assist.image_generations
                                 where status = 'ready' and created_at > now() - interval '30 days') as illustrations,
                               (select coalesce(sum(cost_credits), 0) from assist.image_generations
                                 where created_at > now() - interval '30 days') as credits
                        """)
                .query((rs, n) -> new AiUse(rs.getLong("ideas"), rs.getLong("review_calls"),
                        rs.getLong("illustrations"), rs.getBigDecimal("credits")))
                .single();
    }

    List<PlanCount> plans() {
        return jdbc.sql("""
                        select plan, source, founder, count(*) as accounts
                          from billing.subscriptions
                         where expires_at is null or expires_at > now()
                         group by plan, source, founder
                         order by count(*) desc
                        """)
                .query((rs, n) -> new PlanCount(rs.getString("plan"), rs.getString("source"),
                        rs.getBoolean("founder"), rs.getLong("accounts")))
                .list();
    }
}
