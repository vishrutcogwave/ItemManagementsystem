# Copy this file to deploy.config.psd1 (git-ignored) and fill in real values.
# Never store passwords, passphrases or tokens in this file.
@{
    SshHost           = 'CHANGE_ME'
    SshPort           = 22
    SshUser           = 'deploy'
    SshKeyPath        = 'C:\Users\YOURNAME\.ssh\ims_prod_deploy'
    RemoteIncoming    = '/var/lib/ims-deploy/incoming'
    RemoteBackupDir   = '/var/backups/item-management'
    SiteUrls          = @('https://hiremathwebstudio.in', 'https://www.hiremathwebstudio.in')
    ApiHealthUrl      = 'https://hiremathwebstudio.in/api/health'
    LocalHistoryFile  = 'deploy/.local/history.jsonl'
}
