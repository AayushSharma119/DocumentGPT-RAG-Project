package net.springio.chatpdf;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final JdbcTemplate jdbcTemplate;

    public CustomUserDetailsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        try {
            return jdbcTemplate.queryForObject(
                    "SELECT username, password FROM app_users WHERE username = ?",
                    (rs, rowNum) -> User.withUsername(rs.getString("username"))
                            .password(rs.getString("password"))
                            .roles("USER")
                            .build(),
                    username);
        } catch (Exception e) {
            throw new UsernameNotFoundException("User not found.");
        }
    }
}