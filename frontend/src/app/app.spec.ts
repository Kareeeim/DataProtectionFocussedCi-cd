import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { routes } from './app.routes';
import { AuthService } from './core/services/auth.service';
import { LoginResponse } from './core/models/api.models';

/**
 * Smoke tests for the application shell.
 *
 * These cover the one behaviour that matters at this level: the header is shown
 * to a signed-in user and hidden from an anonymous one. The real authorisation
 * tests live on the backend, where they can actually enforce something.
 */
describe('App shell', () => {
  let httpMock: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    httpMock = TestBed.inject(HttpTestingController);
  });

  /** A well-formed login response, as the API returns it. */
  const session: LoginResponse = {
    accessToken: 'test-token',
    tokenType: 'Bearer',
    expiresIn: 1800,
    user: {
      id: 1,
      email: 'alice@demo.local',
      fullName: 'Alice Rossi',
      role: 'ROLE_CUSTOMER',
    },
  };

  it('creates the application', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });

  it('hides the navigation when nobody is signed in', () => {
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const header = fixture.nativeElement.querySelector('.app-header');
    expect(header).toBeNull();
  });

  it('shows the navigation once a session exists', () => {
    // The session is established through the service's real entry point rather
    // than by reaching into its private state. That matters: if `login` ever
    // stops storing the token, or stores it somewhere the template does not
    // read, this test fails instead of quietly passing.
    const auth = TestBed.inject(AuthService);
    auth.login('alice@demo.local', 'Demo1234!').subscribe();

    httpMock.expectOne('/api/auth/login').flush(session);

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();

    const header = fixture.nativeElement.querySelector('.app-header');
    expect(header).not.toBeNull();
  });

  it('drops the session and the navigation when the user signs out', () => {
    const auth = TestBed.inject(AuthService);
    auth.login('alice@demo.local', 'Demo1234!').subscribe();
    httpMock.expectOne('/api/auth/login').flush(session);

    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.app-header')).not.toBeNull();

    auth.logout();
    fixture.detectChanges();

    // The token is gone from memory, which is the only place it ever was.
    expect(auth.isAuthenticated()).toBe(false);
    expect(fixture.nativeElement.querySelector('.app-header')).toBeNull();
  });
});
