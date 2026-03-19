package su.afk.kemonos.api.video_meta_api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import su.afk.kemonos.api.video_meta_api.application.security.AdminKeyService

/**
 * Защищает админские endpoints через HTTP Basic Auth.
 */
@Configuration
class AdminSecurityConfig(
    private val adminKeyService: AdminKeyService,
    private val adminBruteforceProtectionFilter: AdminBruteforceProtectionFilter,
) {
    @Bean
    @Order(1)
    fun adminSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/actuator/**", "/errors/source", "/api/video/remove")
            .csrf { csrf -> csrf.disable() }
            .formLogin { formLogin -> formLogin.disable() }
            .httpBasic(Customizer.withDefaults())
            .logout { logout -> logout.disable() }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .addFilterBefore(adminBruteforceProtectionFilter, BasicAuthenticationFilter::class.java)
            .authorizeHttpRequests { auth ->
                auth.anyRequest().hasRole("ADMIN")
            }

        return http.build()
    }

    @Bean
    fun adminUserDetailsService(): UserDetailsService =
        UserDetailsService { username ->
            when (username) {
                "admin" -> User.withUsername("admin")
                    .password("{noop}${adminKeyService.currentKey()}")
                    .roles("ADMIN")
                    .build()
                else -> throw UsernameNotFoundException("Unknown admin user: $username")
            }
        }

    @Bean
    @Order(2)
    fun applicationSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .formLogin { formLogin -> formLogin.disable() }
            .httpBasic { httpBasic -> httpBasic.disable() }
            .logout { logout -> logout.disable() }
            .sessionManagement { session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            }
            .authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }

        return http.build()
    }
}
