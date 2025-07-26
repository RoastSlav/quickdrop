function confirmDelete() {
    return confirm("Are you sure you want to delete this file? This action cannot be undone.");
}

function updateCheckboxState(event, checkbox) {
    event.preventDefault();
    const hiddenField = checkbox.form.querySelector('input[name="keepIndefinitely"][type="hidden"]');
    if (hiddenField) {
        hiddenField.value = checkbox.checked;
    }

    console.log('Submitting form...');
    checkbox.form.submit();
}

function initializeModal() {
    const downloadLink = document.getElementById("downloadLink").innerText;
    updateShareLink(downloadLink);
    document.getElementById('unrestrictedLink').checked = false;
    document.getElementById('linkOptions').classList.add('hidden');
    const generateButton = document.getElementById('generateLinkButton');
    generateButton.disabled = true;
    generateButton.classList.add('hidden');
}

function generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads) {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const expirationDate = new Date();
    expirationDate.setDate(expirationDate.getDate() + daysValid);
    const expirationDateStr = expirationDate.toISOString().split('T')[0];

    return fetch(`/api/file/share/${fileUuid}?expirationDate=${expirationDateStr}&nOfDownloads=${allowedNumberOfDownloads}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': csrfToken,
        },
    })
        .then((response) => {
            if (!response.ok) throw new Error("Failed to generate share link");
            return response.text();
        });
}


function copyShareLink() {
    const shareLinkInput = document.getElementById('shareLink');
    navigator.clipboard.writeText(shareLinkInput.value)
        .then(() => {
            alert("Link copied to clipboard!");
        })
        .catch((err) => {
            console.error("Failed to copy link:", err);
        });
}

function createShareLink() {
    const fileUuid = document.getElementById('fileUuid').textContent.trim();
    const daysValidInput = document.getElementById('daysValid');
    const daysValid = parseInt(daysValidInput.value, 10);
    const allowedNumberOfDownloadsInput = document.getElementById('allowedNumberOfDownloadsCount');
    const allowedNumberOfDownloads = parseInt(allowedNumberOfDownloadsInput.value, 10);

    if (isNaN(daysValid) || daysValid < 1) {
        alert("Please enter a valid number of days.");
        return;
    }

    if (isNaN(allowedNumberOfDownloads) || allowedNumberOfDownloads < 1) {
        alert("Please enter a valid number of downloads.");
        return;
    }

    const spinner = document.getElementById('spinner');
    const generateLinkButton = document.getElementById('generateLinkButton');

    spinner.style.display = 'inline-block';
    generateLinkButton.disabled = true;

    generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads)
        .then((shareLink) => {
            updateShareLink(shareLink); // Update with the token-based link
        })
        .catch((error) => {
            console.error(error);
            alert("Failed to generate share link.");
        }).finally(() => {
        spinner.style.display = 'none';
        generateLinkButton.disabled = false;
    });
}

function updateShareLink(link) {
    const shareLinkInput = document.getElementById('shareLink');
    const qrCodeContainer = document.getElementById('shareQRCode');

    shareLinkInput.value = link;
    qrCodeContainer.innerHTML = '';
    QRCode.toCanvas(qrCodeContainer, link, {width: 150, height: 150});
}


function toggleLinkType() {
    const unrestrictedLinkCheckbox = document.getElementById('unrestrictedLink');
    const linkOptions = document.getElementById('linkOptions');
    const generateLinkButton = document.getElementById('generateLinkButton');

    if (unrestrictedLinkCheckbox.checked) {
        linkOptions.classList.remove('hidden');
        generateLinkButton.classList.remove('hidden');
        generateLinkButton.disabled = false;
    } else {
        linkOptions.classList.add('hidden');
        generateLinkButton.classList.add('hidden');
        generateLinkButton.disabled = true;
        initializeModal();
    }
}

function openShareModal() {
    const modal = document.getElementById('shareModal');
    modal.classList.remove('hidden');
    positionShareModal();
}

function closeShareModal() {
    document.getElementById('shareModal').classList.add('hidden');
}

function positionShareModal() {
    const card = document.getElementById('fileInfoCard');
    const modal = document.getElementById('shareModalContent');
    if (!card || !modal) return;

    const margin = 16; // space between modal, card and screen edge
    const cardRect = card.getBoundingClientRect();
    const modalRect = modal.getBoundingClientRect();

    let left = cardRect.left - modalRect.width - margin;
    if (left < margin) {
        left = margin;
    }

    const top = cardRect.top + cardRect.height / 2 - modalRect.height / 2;

    modal.style.left = `${left}px`;
    modal.style.top = `${Math.max(top, margin)}px`;
}

document.addEventListener('DOMContentLoaded', () => {
    initializeModal();
    openShareModal();
    window.addEventListener('resize', positionShareModal);
});
