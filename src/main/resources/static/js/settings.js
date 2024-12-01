function togglePasswordField() {
    const checkbox = document.getElementById("appPasswordEnabled");
    const passwordField = document.getElementById("passwordInputGroup");
    passwordField.style.display = checkbox.checked ? "block" : "none";
}

document.addEventListener("DOMContentLoaded", function () {
    togglePasswordField();
});