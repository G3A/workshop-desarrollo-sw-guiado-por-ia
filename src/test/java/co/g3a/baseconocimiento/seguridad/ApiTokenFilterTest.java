package co.g3a.baseconocimiento.seguridad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;

/**
 * {@link ApiTokenFilter} sin contexto de Spring: es un filtro de servlet puro,
 * no hace falta Postgres ni Ollama para verificar su contrato.
 */
class ApiTokenFilterTest {

    @Test
    @DisplayName("Sin KB_API_TOKEN configurado, deja pasar sin exigir cabecera")
    void sinTokenConfiguradoDejaPasar() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades(""));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filtro.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, rechaza con 401 si falta la cabecera Authorization")
    void conTokenRechazaSinCabecera() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filtro.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Con token configurado, rechaza con 401 si el Bearer no coincide")
    void conTokenRechazaCabeceraIncorrecta() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        request.addHeader("Authorization", "Bearer otro-valor");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filtro.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("Con token configurado y Bearer correcto, deja pasar")
    void conTokenYCabeceraCorrectaDejaPasar() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");
        request.addHeader("Authorization", "Bearer secreto");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filtro.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, /api/messages queda excluido: el Bot Connector valida su propio JWT")
    void mensajesDeTeamsQuedaExcluido() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filtro.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, /api/chat queda excluido: la UI web no tiene login")
    void chatQuedaExcluido() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filtro.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, /api/preview queda excluido: la UI web no tiene login")
    void previewQuedaExcluido() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/preview");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filtro.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, /api/admin/ayuda queda excluido: el boton ? tambien vive en el chat")
    void ayudaQuedaExcluida() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/ayuda");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filtro.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, /api/admin/proyectos queda excluido: el selector tambien vive en el chat")
    void proyectosQuedaExcluido() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/proyectos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filtro.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, /api/vault/contenido queda excluido: el visor de citas tambien vive en el chat")
    void vaultContenidoQuedaExcluido() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/vault/contenido");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filtro.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("Con token configurado, el resto de /api/admin/* si lo exige")
    void restoDeAdminExigeToken() throws Exception {
        ApiTokenFilter filtro = new ApiTokenFilter(new SeguridadPropiedades("secreto"));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/fuentes");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filtro.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }
}
