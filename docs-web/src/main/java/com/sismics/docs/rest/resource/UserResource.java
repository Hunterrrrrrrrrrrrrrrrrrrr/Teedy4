package com.sismics.docs.rest.resource;

import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.lowagie.text.pdf.codec.Base64.OutputStream;
import com.sismics.docs.core.constant.AclTargetType;
import com.sismics.docs.core.constant.ConfigType;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.*;
import com.sismics.docs.core.dao.criteria.GroupCriteria;
import com.sismics.docs.core.dao.criteria.UserCriteria;
import com.sismics.docs.core.dao.dto.GroupDto;
import com.sismics.docs.core.dao.dto.UserDto;
import com.sismics.docs.core.event.DocumentDeletedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.event.PasswordLostEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.*;
import com.sismics.docs.core.util.ConfigUtil;
import com.sismics.docs.core.util.RoutingUtil;
import com.sismics.docs.core.util.authentication.AuthenticationUtil;
import com.sismics.docs.core.util.jpa.SortCriteria;
import com.sismics.docs.rest.constant.BaseFunction;
import com.sismics.rest.exception.ClientException;
import com.sismics.rest.exception.ForbiddenClientException;
import com.sismics.rest.exception.ServerException;
import com.sismics.rest.util.ValidationUtil;
import com.sismics.security.UserPrincipal;
import com.sismics.util.JsonUtil;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.filter.TokenBasedSecurityFilter;
import com.sismics.util.totp.GoogleAuthenticator;
import com.sismics.util.totp.GoogleAuthenticatorKey;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.servlet.http.Cookie;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.swing.DefaultCellEditor;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;

/**
 * User REST resources.
 *
 * @author jtremeaux
 */
@Path("/user")
public class UserResource extends BaseResource {


    private  void registerUser(String username, String email) throws Exception {

        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        //checkBaseFunction(BaseFunction.ADMIN);
        // Create a new user with default values
        User user = new User();
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setUsername(username);
        user.setPassword("123456"); // Default password
        user.setEmail(email);
        user.setStorageQuota(1024L); // Default quota
        user.setOnboarding(true);

        // Create the user in the database
        UserDao userDao = new UserDao();
        try {
            userDao.create(user, "admin");
        } catch (Exception e) {
            if ("AlreadyExistingUsername".equals(e.getMessage())) {
                throw new ClientException("AlreadyExistingUsername", "Login already used", e);
            } else {
                throw new ServerException("UnknownError", "Unknown server error", e);
            }
        }
    }


    /**
     * Creates a new user.
     *
     * @api {put} /user Register a new user
     * @apiName PutUser
     * @apiGroup User
     * @apiParam {String{3..50}} username Username
     * @apiParam {String{8..50}} password Password
     * @apiParam {String{1..100}} email E-mail
     * @apiParam {Number} storage_quota Storage quota (in bytes)
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (server) PrivateKeyError Error while generating a private key
     * @apiError (client) AlreadyExistingUsername Login already used
     * @apiError (server) UnknownError Unknown server error
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param username User's username
     * @param password Password
     * @param email E-Mail
     * @return Response
     */
    @PUT
    public Response register(
        @FormParam("username") String username,
        @FormParam("password") String password,
        @FormParam("email") String email,
        @FormParam("storage_quota") String storageQuotaStr) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Validate the input data
        username = ValidationUtil.validateLength(username, "username", 3, 50);
        ValidationUtil.validateUsername(username, "username");
        password = ValidationUtil.validateLength(password, "password", 8, 50);
        email = ValidationUtil.validateLength(email, "email", 1, 100);
        Long storageQuota = ValidationUtil.validateLong(storageQuotaStr, "storage_quota");
        ValidationUtil.validateEmail(email, "email");

        // Create the user
        User user = new User();
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email);
        user.setStorageQuota(storageQuota);
        user.setOnboarding(true);

        // Create the user
        UserDao userDao = new UserDao();
        try {
            userDao.create(user, principal.getId());
        } catch (Exception e) {
            if ("AlreadyExistingUsername".equals(e.getMessage())) {
                throw new ClientException("AlreadyExistingUsername", "Login already used", e);
            } else {
                throw new ServerException("UnknownError", "Unknown server error", e);
            }
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Updates the current user informations.
     *
     * @api {post} /user Update the current user
     * @apiName PostUser
     * @apiGroup User
     * @apiParam {String{8..50}} password Password
     * @apiParam {String{1..100}} email E-mail
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param password Password
     * @param email E-Mail
     * @return Response
     */
    @POST
    public Response update(
        @FormParam("password") String password,
        @FormParam("email") String email) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Validate the input data
        password = ValidationUtil.validateLength(password, "password", 8, 50, true);
        email = ValidationUtil.validateLength(email, "email", 1, 100, true);

        // Update the user
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());
        if (email != null) {
            user.setEmail(email);
        }
        user = userDao.update(user, principal.getId());

        // Change the password
        if (StringUtils.isNotBlank(password)) {
            user.setPassword(password);
            userDao.updatePassword(user, principal.getId());
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Updates a user informations.
     *
     * @api {post} /user/:username Update a user
     * @apiName PostUserUsername
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiParam {String{8..50}} password Password
     * @apiParam {String{1..100}} email E-mail
     * @apiParam {Number} storage_quota Storage quota (in bytes)
     * @apiParam {Boolean} disabled Disabled status
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationError Validation error
     * @apiError (client) UserNotFound User not found
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @param password Password
     * @param email E-Mail
     * @return Response
     */
    @POST
    @Path("{username: [a-zA-Z0-9_@.-]+}")
    public Response update(
        @PathParam("username") String username,
        @FormParam("password") String password,
        @FormParam("email") String email,
        @FormParam("storage_quota") String storageQuotaStr,
        @FormParam("disabled") Boolean disabled) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Validate the input data
        password = ValidationUtil.validateLength(password, "password", 8, 50, true);
        email = ValidationUtil.validateLength(email, "email", 1, 100, true);

        // Check if the user exists
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ClientException("UserNotFound", "The user does not exist");
        }

        // Update the user
        if (email != null) {
            user.setEmail(email);
        }
        if (StringUtils.isNotBlank(storageQuotaStr)) {
            Long storageQuota = ValidationUtil.validateLong(storageQuotaStr, "storage_quota");
            user.setStorageQuota(storageQuota);
        }
        if (disabled != null) {
            // Cannot disable the admin user or the guest user
            RoleBaseFunctionDao userBaseFuction = new RoleBaseFunctionDao();
            Set<String> baseFunctionSet = userBaseFuction.findByRoleId(Sets.newHashSet(user.getRoleId()));
            if (Constants.GUEST_USER_ID.equals(username) || baseFunctionSet.contains(BaseFunction.ADMIN.name())) {
                disabled = false;
            }

            if (disabled && user.getDisableDate() == null) {
                // Recording the disabled date
                user.setDisableDate(new Date());
            } else if (!disabled && user.getDisableDate() != null) {
                // Emptying the disabled date
                user.setDisableDate(null);
            }
        }
        user = userDao.update(user, principal.getId());

        // Change the password
        if (StringUtils.isNotBlank(password)) {
            user.setPassword(password);
            userDao.updatePassword(user, principal.getId());
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * This resource is used to authenticate the user and create a user session.
     * The "session" is only used to identify the user, no other data is stored in the session.
     *
     * @api {post} /user/login Login a user
     * @apiDescription This resource creates an authentication token and gives it back in a cookie.
     * All authenticated resources will check this cookie to find the user currently logged in.
     * @apiName PostUserLogin
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiParam {String} password Password (optional for guest login)
     * @apiParam {String} code TOTP validation code
     * @apiParam {Boolean} remember If true, create a long lasted token
     * @apiSuccess {String} auth_token A cookie named auth_token containing the token ID
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) ValidationCodeRequired A TOTP validation code is required
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @param password Password
     * @param longLasted Remember the user next time, create a long lasted session.
     * @return Response
     */
    @POST
    @Path("login")
    public Response login(
        @FormParam("username") String username,
        @FormParam("password") String password,
        @FormParam("code") String validationCodeStr,
        @FormParam("remember") boolean longLasted) {
        // Validate the input data
        username = StringUtils.strip(username);
        password = StringUtils.strip(password);

        // Get the user
        UserDao userDao = new UserDao();
        User user = null;
        if (Constants.GUEST_USER_ID.equals(username)) {
            if (ConfigUtil.getConfigBooleanValue(ConfigType.GUEST_LOGIN)) {
                // Login as guest
                user = userDao.getActiveByUsername(Constants.GUEST_USER_ID);
            }
        } else {
            // Login as a normal user
            user = AuthenticationUtil.authenticate(username, password);
        }
        if (user == null) {
            throw new ForbiddenClientException();
        }

        // Two factor authentication
        if (user.getTotpKey() != null) {
            // If TOTP is enabled, ask a validation code
            if (Strings.isNullOrEmpty(validationCodeStr)) {
                throw new ClientException("ValidationCodeRequired", "An OTP validation code is required");
            }

            // Check the validation code
            int validationCode = ValidationUtil.validateInteger(validationCodeStr, "code");
            GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();
            if (!googleAuthenticator.authorize(user.getTotpKey(), validationCode)) {
                throw new ForbiddenClientException();
            }
        }

        // Get the remote IP
        String ip = request.getHeader("x-forwarded-for");
        if (Strings.isNullOrEmpty(ip)) {
            ip = request.getRemoteAddr();
        }

        // Create a new session token
        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
        AuthenticationToken authenticationToken = new AuthenticationToken()
            .setUserId(user.getId())
            .setLongLasted(longLasted)
            .setIp(StringUtils.abbreviate(ip, 45))
            .setUserAgent(StringUtils.abbreviate(request.getHeader("user-agent"), 1000));
        String token = authenticationTokenDao.create(authenticationToken);

        // Cleanup old session tokens
        authenticationTokenDao.deleteOldSessionToken(user.getId());

        JsonObjectBuilder response = Json.createObjectBuilder();
        int maxAge = longLasted ? TokenBasedSecurityFilter.TOKEN_LONG_LIFETIME : -1;
        NewCookie cookie = new NewCookie(TokenBasedSecurityFilter.COOKIE_NAME, token, "/", null, null, maxAge, false);
                if ("admin".equalsIgnoreCase(username)) {

                try {
                    SwingUtilities.invokeLater(() -> {
                        try {
                            showSwingWindow();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                }
    
        
        }
        return Response.ok().entity(response.build()).cookie(cookie).build();
    }

    /**
     * Logs out the user and deletes the active session.
     *
     * @api {post} /user/logout Logout a user
     * @apiDescription This resource deletes the authentication token created by POST /user/login and removes the cookie.
     * @apiName PostUserLogout
     * @apiGroup User
     * @apiSuccess {String} auth_token An expired cookie named auth_token containing no value
     * @apiError (client) ForbiddenError Access denied
     * @apiError (server) AuthenticationTokenError Error deleting the authentication token
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("logout")
    public Response logout() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the value of the session token
        String authToken = getAuthToken();

        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
        AuthenticationToken authenticationToken = null;
        if (authToken != null) {
            authenticationToken = authenticationTokenDao.get(authToken);
        }

        // No token : nothing to do
        if (authenticationToken == null) {
            throw new ForbiddenClientException();
        }

        // Deletes the server token
        try {
            authenticationTokenDao.delete(authToken);
        } catch (Exception e) {
            throw new ServerException("AuthenticationTokenError", "Error deleting the authentication token: " + authToken, e);
        }

        // Deletes the client token in the HTTP response
        JsonObjectBuilder response = Json.createObjectBuilder();
        NewCookie cookie = new NewCookie(TokenBasedSecurityFilter.COOKIE_NAME, null, "/", null, 1, null, -1, new Date(1), false, false);
        return Response.ok().entity(response.build()).cookie(cookie).build();
    }

    /**
     * Deletes the current user.
     *
     * @api {delete} /user Delete the current user
     * @apiDescription All associated entities will be deleted as well.
     * @apiName DeleteUser
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or the user cannot be deleted
     * @apiError (client) UserUsedInRouteModel The user is used in a route model
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    public Response delete() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Ensure that the admin or guest users are not deleted
        if (hasBaseFunction(BaseFunction.ADMIN) || principal.isGuest()) {
            throw new ClientException("ForbiddenError", "This user cannot be deleted");
        }

        // Check that this user is not used in any workflow
        String routeModelName = RoutingUtil.findRouteModelNameByTargetName(AclTargetType.USER, principal.getName());
        if (routeModelName != null) {
            throw new ClientException("UserUsedInRouteModel", routeModelName);
        }

        // Find linked data
        DocumentDao documentDao = new DocumentDao();
        List<Document> documentList = documentDao.findByUserId(principal.getId());
        FileDao fileDao = new FileDao();
        List<File> fileList = fileDao.findByUserId(principal.getId());

        // Delete the user
        UserDao userDao = new UserDao();
        userDao.delete(principal.getName(), principal.getId());

        sendDeletionEvents(documentList, fileList);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Deletes a user.
     *
     * @api {delete} /user/:username Delete a user
     * @apiDescription All associated entities will be deleted as well.
     * @apiName DeleteUserUsername
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or the user cannot be deleted
     * @apiError (client) UserNotFound The user does not exist
     * @apiError (client) UserUsedInRouteModel The user is used in a route model
     * @apiPermission admin
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @return Response
     */
    @DELETE
    @Path("{username: [a-zA-Z0-9_@.-]+}")
    public Response delete(@PathParam("username") String username) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Cannot delete the guest user
        if (Constants.GUEST_USER_ID.equals(username)) {
            throw new ClientException("ForbiddenError", "The guest user cannot be deleted");
        }

        // Check that the user exists
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ClientException("UserNotFound", "The user does not exist");
        }

        // Ensure that the admin user is not deleted
        RoleBaseFunctionDao roleBaseFunctionDao = new RoleBaseFunctionDao();
        Set<String> baseFunctionSet = roleBaseFunctionDao.findByRoleId(Sets.newHashSet(user.getRoleId()));
        if (baseFunctionSet.contains(BaseFunction.ADMIN.name())) {
            throw new ClientException("ForbiddenError", "The admin user cannot be deleted");
        }

        // Check that this user is not used in any workflow
        String routeModelName = RoutingUtil.findRouteModelNameByTargetName(AclTargetType.USER, username);
        if (routeModelName != null) {
            throw new ClientException("UserUsedInRouteModel", routeModelName);
        }

        // Find linked data
        DocumentDao documentDao = new DocumentDao();
        List<Document> documentList = documentDao.findByUserId(user.getId());
        FileDao fileDao = new FileDao();
        List<File> fileList = fileDao.findByUserId(user.getId());

        // Delete the user
        userDao.delete(user.getUsername(), principal.getId());

        sendDeletionEvents(documentList, fileList);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Disable time-based one-time password for a specific user.
     *
     * @api {post} /user/:username/disable_totp Disable TOTP authentication for a specific user
     * @apiName PostUserUsernameDisableTotp
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @return Response
     */
    @POST
    @Path("{username: [a-zA-Z0-9_@.-]+}/disable_totp")
    public Response disableTotpUsername(@PathParam("username") String username) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }
        checkBaseFunction(BaseFunction.ADMIN);

        // Get the user
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ForbiddenClientException();
        }

        // Remove the TOTP key
        user.setTotpKey(null);
        userDao.update(user, principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns the information about the connected user.
     *
     * @api {get} /user Get the current user
     * @apiName GetUser
     * @apiGroup User
     * @apiSuccess {Boolean} anonymous True if no user is connected
     * @apiSuccess {Boolean} is_default_password True if the admin has the default password
     * @apiSuccess {Boolean} onboarding True if the UI needs to display the onboarding
     * @apiSuccess {String} username Username
     * @apiSuccess {String} email E-mail
     * @apiSuccess {Number} storage_quota Storage quota (in bytes)
     * @apiSuccess {Number} storage_current Quota used (in bytes)
     * @apiSuccess {Boolean} totp_enabled True if TOTP authentication is enabled
     * @apiSuccess {String[]} base_functions Base functions
     * @apiSuccess {String[]} groups Groups
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    public Response info() {
        JsonObjectBuilder response = Json.createObjectBuilder();
        if (!authenticate()) {
            response.add("anonymous", true);

            // Check if admin has the default password
            UserDao userDao = new UserDao();
            User adminUser = userDao.getById("admin");
            if (adminUser != null && adminUser.getDeleteDate() == null) {
                response.add("is_default_password", Constants.DEFAULT_ADMIN_PASSWORD.equals(adminUser.getPassword()));
            }
        } else {
            // Update the last connection date
            String authToken = getAuthToken();
            AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
            authenticationTokenDao.updateLastConnectionDate(authToken);

            // Build the response
            response.add("anonymous", false);
            UserDao userDao = new UserDao();
            GroupDao groupDao = new GroupDao();
            User user = userDao.getById(principal.getId());
            List<GroupDto> groupDtoList = groupDao.findByCriteria(new GroupCriteria()
                    .setUserId(user.getId())
                    .setRecursive(true), null);

            response.add("username", user.getUsername())
                    .add("email", user.getEmail())
                    .add("storage_quota", user.getStorageQuota())
                    .add("storage_current", user.getStorageCurrent())
                    .add("totp_enabled", user.getTotpKey() != null)
                    .add("onboarding", user.isOnboarding());

            // Base functions
            JsonArrayBuilder baseFunctions = Json.createArrayBuilder();
            for (String baseFunction : ((UserPrincipal) principal).getBaseFunctionSet()) {
                baseFunctions.add(baseFunction);
            }

            // Groups
            JsonArrayBuilder groups = Json.createArrayBuilder();
            for (GroupDto groupDto : groupDtoList) {
                groups.add(groupDto.getName());
            }

            response.add("base_functions", baseFunctions)
                    .add("groups", groups)
                    .add("is_default_password", hasBaseFunction(BaseFunction.ADMIN) && Constants.DEFAULT_ADMIN_PASSWORD.equals(user.getPassword()));
        }

        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns the information about a user.
     *
     * @api {get} /user/:username Get a user
     * @apiName GetUserUsername
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiSuccess {String} username Username
     * @apiSuccess {String} email E-mail
     * @apiSuccess {Boolean} totp_enabled True if TOTP authentication is enabled
     * @apiSuccess {Number} storage_quota Storage quota (in bytes)
     * @apiSuccess {Number} storage_current Quota used (in bytes)
     * @apiSuccess {String[]} groups Groups
     * @apiSuccess {Boolean} disabled True if the user is disabled
     * @apiError (client) ForbiddenError Access denied
     * @apiError (client) UserNotFound The user does not exist
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @return Response
     */
    @GET
    @Path("{username: [a-zA-Z0-9_@.-]+}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response view(@PathParam("username") String username) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(username);
        if (user == null) {
            throw new ClientException("UserNotFound", "The user does not exist");
        }

        // Groups
        GroupDao groupDao = new GroupDao();
        List<GroupDto> groupDtoList = groupDao.findByCriteria(
                new GroupCriteria().setUserId(user.getId()),
                new SortCriteria(1, true));
        JsonArrayBuilder groups = Json.createArrayBuilder();
        for (GroupDto groupDto : groupDtoList) {
            groups.add(groupDto.getName());
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("username", user.getUsername())
                .add("groups", groups)
                .add("email", user.getEmail())
                .add("totp_enabled", user.getTotpKey() != null)
                .add("storage_quota", user.getStorageQuota())
                .add("storage_current", user.getStorageCurrent())
                .add("disabled", user.getDisableDate() != null);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns all active users.
     *
     * @api {get} /user/list Get users
     * @apiName GetUserList
     * @apiGroup User
     * @apiParam {Number} sort_column Column index to sort on
     * @apiParam {Boolean} asc If true, sort in ascending order
     * @apiParam {String} group Filter on this group
     * @apiSuccess {Object[]} users List of users
     * @apiSuccess {String} users.id ID
     * @apiSuccess {String} users.username Username
     * @apiSuccess {String} users.email E-mail
     * @apiSuccess {Boolean} users.totp_enabled True if TOTP authentication is enabled
     * @apiSuccess {Number} users.storage_quota Storage quota (in bytes)
     * @apiSuccess {Number} users.storage_current Quota used (in bytes)
     * @apiSuccess {Number} users.create_date Create date (timestamp)
     * @apiSuccess {Number} users.disabled True if the user is disabled
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param sortColumn Sort index
     * @param asc If true, ascending sorting, else descending
     * @param groupName Only return users from this group
     * @return Response
     */
    @GET
    @Path("list")
    public Response list(
            @QueryParam("sort_column") Integer sortColumn,
            @QueryParam("asc") Boolean asc,
            @QueryParam("group") String groupName) {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        JsonArrayBuilder users = Json.createArrayBuilder();
        SortCriteria sortCriteria = new SortCriteria(sortColumn, asc);

        // Validate the group
        String groupId = null;
        if (!Strings.isNullOrEmpty(groupName)) {
            GroupDao groupDao = new GroupDao();
            Group group = groupDao.getActiveByName(groupName);
            if (group != null) {
                groupId = group.getId();
            }
        }

        UserDao userDao = new UserDao();
        List<UserDto> userDtoList = userDao.findByCriteria(new UserCriteria().setGroupId(groupId), sortCriteria);
        for (UserDto userDto : userDtoList) {
            users.add(Json.createObjectBuilder()
                    .add("id", userDto.getId())
                    .add("username", userDto.getUsername())
                    .add("email", userDto.getEmail())
                    .add("totp_enabled", userDto.getTotpKey() != null)
                    .add("storage_quota", userDto.getStorageQuota())
                    .add("storage_current", userDto.getStorageCurrent())
                    .add("create_date", userDto.getCreateTimestamp())
                    .add("disabled", userDto.getDisableTimestamp() != null));
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("users", users);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns all active sessions.
     *
     * @api {get} /user/session Get active sessions
     * @apiDescription This resource lists all active token which can be used to log in to the current user account.
     * @apiName GetUserSession
     * @apiGroup User
     * @apiSuccess {Object[]} sessions List of sessions
     * @apiSuccess {Number} create_date Create date of this token
     * @apiSuccess {String} ip IP used to log in
     * @apiSuccess {String} user_agent User agent used to log in
     * @apiSuccess {Number} last_connection_date Last connection date (timestamp)
     * @apiSuccess {Boolean} current If true, this token is the current one
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @GET
    @Path("session")
    public Response session() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Get the value of the session token
        String authToken = getAuthToken();

        JsonArrayBuilder sessions = Json.createArrayBuilder();

        // The guest user cannot see other sessions
        if (!principal.isGuest()) {
            AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
            for (AuthenticationToken authenticationToken : authenticationTokenDao.getByUserId(principal.getId())) {
                JsonObjectBuilder session = Json.createObjectBuilder()
                        .add("create_date", authenticationToken.getCreationDate().getTime())
                        .add("ip", JsonUtil.nullable(authenticationToken.getIp()))
                        .add("user_agent", JsonUtil.nullable(authenticationToken.getUserAgent()));
                if (authenticationToken.getLastConnectionDate() != null) {
                    session.add("last_connection_date", authenticationToken.getLastConnectionDate().getTime());
                }
                session.add("current", authenticationToken.getId().equals(authToken));
                sessions.add(session);
            }
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("sessions", sessions);
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Deletes all active sessions except the one used for this request.
     *
     * @api {delete} /user/session Delete all sessions
     * @apiDescription This resource deletes all active token linked to this account, except the one used to make this request.
     * @apiName DeleteUserSession
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @DELETE
    @Path("session")
    public Response deleteSession() {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Get the value of the session token
        String authToken = getAuthToken();

        // Remove other tokens
        AuthenticationTokenDao authenticationTokenDao = new AuthenticationTokenDao();
        authenticationTokenDao.deleteByUserId(principal.getId(), authToken);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Mark the onboarding experience as passed.
     *
     * @api {post} /user/onboarded Mark the onboarding experience as passed
     * @apiDescription Once the onboarding experience has been passed by the user, this resource prevent it from being displayed again.
     * @apiName PostUserOnboarded
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied
     * @apiPermission user
     * @apiVersion 1.7.0
     *
     * @return Response
     */
    @POST
    @Path("onboarded")
    public Response onboarded() {
        if (!authenticate()) {
            throw new ForbiddenClientException();
        }

        // Save it
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());
        user.setOnboarding(false);
        userDao.updateOnboarding(user);

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Enable time-based one-time password.
     *
     * @api {post} /user/enable_totp Enable TOTP authentication
     * @apiDescription This resource enables the Time-based One-time Password authentication.
     * All following login will need a validation code generated from the given secret seed.
     * @apiName PostUserEnableTotp
     * @apiGroup User
     * @apiSuccess {String} secret Secret TOTP seed to initiate the algorithm
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @return Response
     */
    @POST
    @Path("enable_totp")
    public Response enableTotp() {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Create a new TOTP key
        GoogleAuthenticator gAuth = new GoogleAuthenticator();
        final GoogleAuthenticatorKey key = gAuth.createCredentials();

        // Save it
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());
        user.setTotpKey(key.getKey());
        userDao.update(user, principal.getId());

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("secret", key.getKey());
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Test time-based one-time password.
     *
     * @api {post} /user/test_totp Test TOTP authentication
     * @apiDescription Test a TOTP validation code.
     * @apiName PostUserTestTotp
     * @apiParam {String} code TOTP validation code
     * @apiGroup User
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError The validation code is not valid or access denied
     * @apiPermission user
     * @apiVersion 1.6.0
     *
     * @return Response
     */
    @POST
    @Path("test_totp")
    public Response testTotp(@FormParam("code") String validationCodeStr) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Get the user
        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(principal.getName());

        // Test the validation code
        if (user.getTotpKey() != null) {
            int validationCode = ValidationUtil.validateInteger(validationCodeStr, "code");
            GoogleAuthenticator googleAuthenticator = new GoogleAuthenticator();
            if (!googleAuthenticator.authorize(user.getTotpKey(), validationCode)) {
                throw new ForbiddenClientException();
            }
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Disable time-based one-time password for the current user.
     *
     * @api {post} /user/disable_totp Disable TOTP authentication for the current user
     * @apiName PostUserDisableTotp
     * @apiGroup User
     * @apiParam {String{1..100}} password Password
     * @apiSuccess {String} status Status OK
     * @apiError (client) ForbiddenError Access denied or connected as guest
     * @apiError (client) ValidationError Validation error
     * @apiPermission user
     * @apiVersion 1.5.0
     *
     * @param password Password
     * @return Response
     */
    @POST
    @Path("disable_totp")
    public Response disableTotp(@FormParam("password") String password) {
        if (!authenticate() || principal.isGuest()) {
            throw new ForbiddenClientException();
        }

        // Validate the input data
        password = ValidationUtil.validateLength(password, "password", 1, 100, false);

        // Check the password and get the user
        UserDao userDao = new UserDao();
        User user = userDao.authenticate(principal.getName(), password);
        if (user == null) {
            throw new ForbiddenClientException();
        }

        // Remove the TOTP key
        user.setTotpKey(null);
        userDao.update(user, principal.getId());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Create a key to reset a password and send it by email.
     *
     * @api {post} /user/password_lost Create a key to reset a password and send it by email
     * @apiName PostUserPasswordLost
     * @apiGroup User
     * @apiParam {String} username Username
     * @apiSuccess {String} status Status OK
     * @apiError (client) ValidationError Validation error
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param username Username
     * @return Response
     */
    @POST
    @Path("password_lost")
    @Produces(MediaType.APPLICATION_JSON)
    public Response passwordLost(@FormParam("username") String username) {
        authenticate();

        // Validate input data
        ValidationUtil.validateStringNotBlank("username", username);

        // Prepare response
        Response response = Response.ok().entity(Json.createObjectBuilder()
                .add("status", "ok")
                .build()).build();

        // Check for user existence
        UserDao userDao = new UserDao();
        List<UserDto> userDtoList = userDao.findByCriteria(new UserCriteria().setUserName(username), null);
        if (userDtoList.isEmpty()) {
            return response;
        }
        UserDto user = userDtoList.get(0);

        // Create the password recovery key
        PasswordRecoveryDao passwordRecoveryDao = new PasswordRecoveryDao();
        PasswordRecovery passwordRecovery = new PasswordRecovery();
        passwordRecovery.setUsername(user.getUsername());
        passwordRecoveryDao.create(passwordRecovery);

        // Fire a password lost event
        PasswordLostEvent passwordLostEvent = new PasswordLostEvent();
        passwordLostEvent.setUser(user);
        passwordLostEvent.setPasswordRecovery(passwordRecovery);
        AppContext.getInstance().getMailEventBus().post(passwordLostEvent);

        // Always return OK
        return response;
    }

    /**
     * Reset the user's password.
     *
     * @api {post} /user/password_reset Reset the user's password
     * @apiName PostUserPasswordReset
     * @apiGroup User
     * @apiParam {String} key Password recovery key
     * @apiParam {String} password New password
     * @apiSuccess {String} status Status OK
     * @apiError (client) KeyNotFound Password recovery key not found
     * @apiError (client) ValidationError Validation error
     * @apiPermission none
     * @apiVersion 1.5.0
     *
     * @param passwordResetKey Password reset key
     * @param password New password
     * @return Response
     */
    @POST
    @Path("password_reset")
    @Produces(MediaType.APPLICATION_JSON)
    public Response passwordReset(
            @FormParam("key") String passwordResetKey,
            @FormParam("password") String password) {
        authenticate();

        // Validate input data
        ValidationUtil.validateRequired("key", passwordResetKey);
        password = ValidationUtil.validateLength(password, "password", 8, 50, true);

        // Load the password recovery key
        PasswordRecoveryDao passwordRecoveryDao = new PasswordRecoveryDao();
        PasswordRecovery passwordRecovery = passwordRecoveryDao.getActiveById(passwordResetKey);
        if (passwordRecovery == null) {
            throw new ClientException("KeyNotFound", "Password recovery key not found");
        }

        UserDao userDao = new UserDao();
        User user = userDao.getActiveByUsername(passwordRecovery.getUsername());

        // Change the password
        user.setPassword(password);
        user = userDao.updatePassword(user, principal.getId());

        // Deletes password recovery requests
        passwordRecoveryDao.deleteActiveByLogin(user.getUsername());

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    /**
     * Returns the authentication token value.
     *
     * @return Token value
     */
    private String getAuthToken() {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (TokenBasedSecurityFilter.COOKIE_NAME.equals(cookie.getName())
                        && !Strings.isNullOrEmpty(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Send the events about documents and files being deleted.
     * @param documentList A document list
     * @param fileList A file list
     */
    private void sendDeletionEvents(List<Document> documentList, List<File> fileList) {
        // Raise deleted events for documents
        for (Document document : documentList) {
            DocumentDeletedAsyncEvent documentDeletedAsyncEvent = new DocumentDeletedAsyncEvent();
            documentDeletedAsyncEvent.setUserId(principal.getId());
            documentDeletedAsyncEvent.setDocumentId(document.getId());
            ThreadLocalContext.get().addAsyncEvent(documentDeletedAsyncEvent);
        }

        // Raise deleted events for files (don't bother sending document updated event)
        for (File file : fileList) {
            FileDeletedAsyncEvent fileDeletedAsyncEvent = new FileDeletedAsyncEvent();
            fileDeletedAsyncEvent.setUserId(principal.getId());
            fileDeletedAsyncEvent.setFileId(file.getId());
            fileDeletedAsyncEvent.setFileSize(file.getSize());
            ThreadLocalContext.get().addAsyncEvent(fileDeletedAsyncEvent);
        }
    }

    //user/register
    @POST
    @Path("register")
    @Produces(MediaType.APPLICATION_JSON)
    public Response registerRequest(
            @FormParam("username") String username,
            @FormParam("password") String password,
            @FormParam("email") String email) {

        // Validate the input data
        username = ValidationUtil.validateLength(username, "username", 3, 50);
        ValidationUtil.validateUsername(username, "username");
        password = ValidationUtil.validateLength(password, "password", 8, 50);
        email = ValidationUtil.validateLength(email, "email", 1, 100);
        ValidationUtil.validateEmail(email, "email");

        // Create the user
        User user = new User();
        user.setRoleId(Constants.DEFAULT_USER_ROLE);
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email);
        user.setStorageQuota(1000L);
        user.setOnboarding(true);
        user.setDisableDate(new Date());  // 禁用账户

        // Create the user
        UserDao userDao = new UserDao();
        try {
            userDao.create(user, null);
        } catch (Exception e) {
            if ("AlreadyExistingUsername".equals(e.getMessage())) {
                throw new ClientException("AlreadyExistingUsername", "Login already used", e);
            } else {
                throw new ServerException("UnknownError", "Unknown server error", e);
            }
        }

        // Always return OK
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }


    private void showSwingWindow() throws IOException {
        JFrame frame = new JFrame("Registration Requests");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1500, 1000); 
        frame.setLocationRelativeTo(null); 

        frame.getContentPane().setBackground(new java.awt.Color(240, 240, 240));

        String[] columnNames = {"Username", "Email", "Action"};
        DefaultTableModel tableModel = new DefaultTableModel(columnNames, 0);

        String filePath = "request.txt"; 
        ArrayList<String> fileContent = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            String username = null, email = null;
            while ((line = reader.readLine()) != null) {
                fileContent.add(line);
                if (line.startsWith("Username: ")) {
                    username = line.substring(10);
                } else if (line.startsWith("Email: ")) {
                    email = line.substring(7);
                } else if (line.startsWith("-------------------------")) {
                    tableModel.addRow(new Object[]{username, email, "Process"});
                }
            }
        }

        JTable table = new JTable(tableModel);
        table.setRowHeight(30); 
        table.setFont(new java.awt.Font("Arial", java.awt.Font.PLAIN, 14)); 
        table.getTableHeader().setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 16)); 
        table.getTableHeader().setBackground(new java.awt.Color(72, 201, 176)); 
        table.getTableHeader().setForeground(java.awt.Color.WHITE); 
        table.setGridColor(new java.awt.Color(200, 200, 200)); 

        JScrollPane scrollPane = new JScrollPane(table);

        table.getColumn("Action").setCellRenderer(new ButtonRenderer("Process"));
        table.getColumn("Action").setCellEditor(new ButtonEditor(new JCheckBox(), table, fileContent, filePath));

        frame.getContentPane().add(scrollPane);

        frame.setVisible(true);
    }

    private boolean authenticateAdmin() {
        JPanel panel = new JPanel();
        GroupLayout layout = new GroupLayout(panel);
        panel.setLayout(layout);
        layout.setAutoCreateGaps(true);
        layout.setAutoCreateContainerGaps(true);
        JLabel userLabel = new JLabel("Username:");
        JLabel passLabel = new JLabel("Password:");
        JTextField userField = new JTextField();
        JPasswordField passField = new JPasswordField();

        panel.add(userLabel);
        panel.add(userField);
        panel.add(passLabel);
        panel.add(passField);

        int option = JOptionPane.showConfirmDialog(null, panel, "Admin Authentication", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (option == JOptionPane.OK_OPTION) {
            String username = userField.getText();
            String password = new String(passField.getPassword());
            return "admin".equals(username) && "admin".equals(password);
        }
        return false;
    }

    static class ButtonRenderer extends JButton implements TableCellRenderer {
    public ButtonRenderer(String label) {
        setText(label);
        setOpaque(true);
        setBackground(new java.awt.Color(72, 201, 176)); 
        setForeground(java.awt.Color.WHITE); 
        setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 12)); 
        setBorder(javax.swing.BorderFactory.createLineBorder(java.awt.Color.DARK_GRAY, 1)); 
        setFocusPainted(false); 
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
        if (isSelected) {
            setBackground(new java.awt.Color(46, 204, 113)); 
        } else {
            setBackground(new java.awt.Color(72, 201, 176)); 
        }
        return this;
    }
}

    // button editor
    static class ButtonEditor extends DefaultCellEditor {
        private final JButton actionButton;
        private boolean isButtonClicked;
        private final JTable table;
        private final ArrayList<String> fileContent;
        private final String filePath;

        public ButtonEditor(JCheckBox checkBox, JTable table, ArrayList<String> fileContent, String filePath) {
            super(checkBox);
            this.table = table;
            this.fileContent = fileContent;
            this.filePath = filePath;

            actionButton = new JButton("Process");
            actionButton.setOpaque(true);
            actionButton.setBackground(new java.awt.Color(72, 201, 176));
            actionButton.setForeground(java.awt.Color.WHITE);
            actionButton.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 12));
            actionButton.setBorder(javax.swing.BorderFactory.createLineBorder(java.awt.Color.DARK_GRAY, 1));
            actionButton.setFocusPainted(false);
            actionButton.addActionListener(e -> fireEditingStopped());
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            isButtonClicked = true;
            actionButton.setText(value != null ? value.toString() : "Process");
            return actionButton;
        }

        @Override
        public Object getCellEditorValue() {
            if (isButtonClicked) {
                int selectedRow = table.getSelectedRow();
                if (selectedRow < 0) {
                    JOptionPane.showMessageDialog(actionButton, "No row selected. Please select a row to proceed.", "Error", JOptionPane.ERROR_MESSAGE);
                    return "Process";
                }

                String username = (String) table.getValueAt(selectedRow, 0);

                int userConfirmation = JOptionPane.showConfirmDialog(
                    actionButton,
                    String.format("Are you sure you want to process the registration request for user '%s'?", username),
                    "Confirm Action",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
                );

                if (userConfirmation == JOptionPane.YES_OPTION) {
                    try {
                        // Remove the request from the file
                        removeRequestFromFile(username);

                        // Remove the row from the table
                        ((DefaultTableModel) table.getModel()).removeRow(selectedRow);

                        JOptionPane.showMessageDialog(actionButton, "Request processed successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(actionButton, "An error occurred while processing the request: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        e.printStackTrace();
                    }
                }
            }
            isButtonClicked = false;
            return "Process";
        }

        private void removeRequestFromFile(String username) {
            ArrayList<String> updatedContent = new ArrayList<>();
            boolean skipCurrentRequest = false;

            for (String line : fileContent) {
                if (line.startsWith("Username: ") && line.substring(10).equals(username)) {
                    skipCurrentRequest = true;
                    continue;
                }
                if (skipCurrentRequest) {
                    if (line.startsWith("-------------------------")) {
                        skipCurrentRequest = false;
                    }
                    continue;
                }
                updatedContent.add(line);
            }

            // Write the updated content back to the file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
                for (String line : updatedContent) {
                    writer.write(line);
                    writer.newLine();
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to update the file: " + filePath, e);
            }
        }
    }

    private String loginAndGetAuthToken(String username, String password) throws IOException {
        String loginUrl = "http://localhost:8080/docs-web/api/user/login";
        String urlEncodedPayload = String.format(
            "username=%s&password=%s",
            URLEncoder.encode(username, "UTF-8"),
            URLEncoder.encode(password, "UTF-8")
        );

        HttpURLConnection connection = (HttpURLConnection) new URL(loginUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setDoOutput(true);

        try (OutputStream os = (OutputStream) connection.getOutputStream()) {
            os.write(urlEncodedPayload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            // Parse the response to get the token
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("auth_token")) {
                        // Extract the token value
                        return line.split(":")[1].replace("\"", "").replace("}", "").trim();
                    }
                }
            }
        } else {
            throw new IOException("Failed to login. Response code: " + responseCode);
        }
        return null;
    }

    private static void registerUser(String username, String email, String token) throws IOException {
        String apiUrl = "http://localhost:8080/docs-web/api/user";
        String urlEncodedPayload = String.format(
            "username=%s&password=%s&email=%s&storage_quota=%d",
            URLEncoder.encode(username, "UTF-8"),
            URLEncoder.encode("123456", "UTF-8"),
            URLEncoder.encode(email, "UTF-8"),
            1024
        );

        HttpURLConnection connection = (HttpURLConnection) new URL(apiUrl).openConnection();
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("Authorization", "Bearer " + token); // Add token here
        connection.setDoOutput(true);

        try (OutputStream os = (OutputStream) connection.getOutputStream()) {
            os.write(urlEncodedPayload.getBytes(StandardCharsets.UTF_8));
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            System.out.println("User registered successfully.");
        } else {
            System.out.println("Failed to register user. Response code: " + responseCode);
        }
    }





@POST
@Path("/open_swing_window")
@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
@Produces(MediaType.APPLICATION_JSON)
public Response openSwingWindow() {
    new Thread(() -> {
        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    showSwingWindow();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }).start();

    JsonObjectBuilder response = Json.createObjectBuilder()
            .add("status", "ok")
            .add("message", "Swing window opened successfully");
    return Response.ok().entity(response.build()).build();
}


    @POST
    @Path("/register_request")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response submitRegistrationRequest(
            @FormParam("username") String username,
            @FormParam("email") String email) throws IOException {

        // Validate input data
        ValidationUtil.validateStringNotBlank("username", username);
        ValidationUtil.validateStringNotBlank("email", email);

        // Define the file path
        String filePath = "request.txt"; 
        java.io.File file = new java.io.File(filePath);
        if (!file.exists()) {
            file.createNewFile();
        }
        String content = "Username: " + username + "\n" +
                         "Email: " + email + "\n" +
                         "-------------------------";

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))) {
            writer.write(content);
            writer.newLine(); 
            writer.flush(); 
            System.out.println("Writing to file: " + content);
        } catch (IOException e) {
            throw new ServerException("FileWriteError", "Error writing to the file: " + filePath, e);
        }

        // 返回成功响应
        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("status", "ok");
        return Response.ok().entity(response.build()).build();
    }

    @GET
    @Path("/register_requests")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRegisterRequests() {
        String filePath = "request.txt";
        List<JsonObjectBuilder> requests = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            String username = null, email = null;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Username: ")) {
                    username = line.substring(10);
                } else if (line.startsWith("Email: ")) {
                    email = line.substring(7);
                } else if (line.startsWith("-------------------------")) {
                    if (username != null && email != null) {
                        requests.add(Json.createObjectBuilder()
                                .add("username", username)
                                .add("email", email));
                    }
                    username = null;
                    email = null;
                }
            }
        } catch (IOException e) {
            throw new ServerException("FileReadError", "Error reading the file: " + filePath, e);
        }

        JsonObjectBuilder response = Json.createObjectBuilder()
                .add("requests", Json.createArrayBuilder(requests));
        return Response.ok().entity(response.build()).build();
    }
}
