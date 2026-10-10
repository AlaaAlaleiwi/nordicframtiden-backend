package com.nordicframtiden.api;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nordicframtiden.contact.ContactRequestService;
import com.nordicframtiden.security.jwt.JwtService;
import com.nordicframtiden.security.repo.AppUserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Contact requests hold submitters' personal data: staff need PERM_CONTACTS. */
@WebMvcTest(ContactController.class)
@AutoConfigureMockMvc
@Import(ContactControllerSecurityTest.MethodSecurityTestConfig.class)
class ContactControllerSecurityTest {

  @TestConfiguration
  @EnableMethodSecurity
  static class MethodSecurityTestConfig {
  }

  @jakarta.annotation.Resource
  MockMvc mvc;

  @MockitoBean ContactRequestService service;
  @MockitoBean JwtService jwtService;
  @MockitoBean AppUserRepository userRepo;

  @Test
  @WithMockUser(authorities = {"ROLE_STAFF", "PERM_PEOPLE"})
  void staffWithoutContactsPermissionCannotReadOrDelete() throws Exception {
    mvc.perform(get("/api/contact")).andExpect(status().isForbidden());
    mvc.perform(delete("/api/contact/5").with(csrf())).andExpect(status().isForbidden());
    verify(service, never()).list();
    verify(service, never()).delete(5L);
  }

  @Test
  @WithMockUser(authorities = {"ROLE_STAFF", "PERM_CONTACTS"})
  void staffWithContactsPermissionCanRead() throws Exception {
    when(service.list()).thenReturn(List.of());
    mvc.perform(get("/api/contact")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void adminCanDelete() throws Exception {
    mvc.perform(delete("/api/contact/5").with(csrf())).andExpect(status().isOk());
    verify(service).delete(5L);
  }
}
