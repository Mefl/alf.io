/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.config.authentication;

import alfio.config.authentication.support.*;
import alfio.model.user.User;
import alfio.repository.user.AuthorityRepository;
import alfio.repository.user.UserRepository;
import alfio.util.ClockProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.ZonedDateTime;
import java.util.stream.Collectors;

import static alfio.config.authentication.support.AuthenticationConstants.*;

@Configuration(proxyBeanMethods = false)
@Order(0)
public class APITokenAuthWebSecurity extends WebSecurityConfigurerAdapter {

    public static final String API_KEY = "Api key ";
    private final UserRepository userRepository;
    private final AuthorityRepository authorityRepository;

    public APITokenAuthWebSecurity(UserRepository userRepository,
                                   AuthorityRepository authorityRepository) {
        this.userRepository = userRepository;
        this.authorityRepository = authorityRepository;
    }

    //https://stackoverflow.com/a/48448901
    @Override
    protected void configure(HttpSecurity http) throws Exception {

        APIKeyAuthFilter filter = new APIKeyAuthFilter();
        filter.setAuthenticationManager(authentication -> {
            //
            String apiKey = (String) authentication.getPrincipal();
            //check if user type ->
            User user = userRepository.findByUsername(apiKey).orElseThrow(() -> new BadCredentialsException(API_KEY + apiKey + " don't exists"));
            if (!user.isEnabled()) {
                throw new DisabledException(API_KEY + apiKey + " is disabled");
            }
            if (User.Type.API_KEY != user.getType()) {
                throw new WrongAccountTypeException("Wrong account type for username " + apiKey);
            }
            if (!user.isCurrentlyValid(ZonedDateTime.now(ClockProvider.clock()))) {
                throw new DisabledException(API_KEY + apiKey + " is expired");
            }

            return new APITokenAuthentication(
                authentication.getPrincipal(),
                authentication.getCredentials(),
                authorityRepository.findRoles(apiKey).stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList()));
        });


        http.requestMatcher(RequestTypeMatchers::isTokenAuthentication)
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and().csrf().disable()
            .authorizeRequests()
            .antMatchers(ADMIN_PUBLIC_API + "/**").hasRole(API_CLIENT)
            .antMatchers(ADMIN_API + "/check-in/**").hasAnyRole(OPERATOR, SUPERVISOR)
            .antMatchers(HttpMethod.GET, ADMIN_API + "/events").hasAnyRole(OPERATOR, SUPERVISOR, AuthenticationConstants.SPONSOR)
            .antMatchers(HttpMethod.GET, ADMIN_API + "/user-type", ADMIN_API + "/user/details").hasAnyRole(OPERATOR, SUPERVISOR, AuthenticationConstants.SPONSOR)
            .antMatchers(ADMIN_API + "/**").denyAll()
            .antMatchers(HttpMethod.POST, "/api/attendees/sponsor-scan").hasRole(AuthenticationConstants.SPONSOR)
            .antMatchers(HttpMethod.GET, "/api/attendees/*/ticket/*").hasAnyRole(OPERATOR, SUPERVISOR, API_CLIENT)
            .antMatchers("/**").authenticated()
            .and().addFilter(filter);
    }
}
